package dk.trustworks.intranet.signing.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.documentservice.maintenance.EmployeeDocumentCategorizerRules;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.StoreCommand;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.services.GeneratedPdfRef;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import dk.trustworks.intranet.utils.services.SigningService;
import dk.trustworks.intranet.utils.services.SigningService.SignedDocumentDownload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * S3 archival of completed signing cases (employee-documents spec
 * §6.5.1–2), driven by {@code NextSignStatusSyncBatchlet} both inline at
 * completion and from its bounded catch-up sweep.
 *
 * <p><b>Employee-flow cases</b> archive each signed PDF into the case
 * owner's employee document store ({@code source=SIGNING}, category
 * mapped from the case's template). Idempotent per
 * {@code uq_ed_signing(signing_case_key, document_index)} — a retried
 * pass simply completes the missing rows.</p>
 *
 * <p><b>Recruitment-flow cases</b> (detected via
 * {@code candidate_dossier_revisions.signing_case_key}) archive into the
 * CANDIDATE's staging space instead ({@code trustworksfiles},
 * {@code files.relateduuid = candidateUuid}) and record the refs in the
 * revision's {@code signed_pdfs_snapshot} — durable from minute one,
 * promoted at conversion, auto-covered by the candidate GDPR engine.</p>
 *
 * <p>Failures leave {@code archive_status = PENDING} + an
 * {@code archive_error}; the next 5-minute pass retries.
 * {@code PARTIAL_FAILURE} deliberately does not exist as a state — a
 * half-archived multi-document case is simply PENDING with some rows
 * already present (spec §6.5.1).</p>
 */
@JBossLog
@ApplicationScoped
public class EmployeeSigningArchivalService {

    private static final DateTimeFormatter FILENAME_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    /**
     * Failed archival attempts after which a case is abandoned as SKIPPED
     * (V551). At the sweep's 5-minute cadence this is roughly two hours of
     * retrying, which comfortably outlasts a transient NextSign or S3 blip
     * while still terminating on a permanently expired envelope.
     *
     * <p>Operational note: an admin who resets an abandoned case to PENDING
     * must also zero {@code archive_attempts}, or the next single failure
     * re-abandons it immediately.</p>
     */
    static final int MAX_ARCHIVE_ATTEMPTS = 24;

    @Inject
    SigningService signingService;

    @Inject
    SigningCaseRepository signingCaseRepository;

    @Inject
    EmployeeDocumentService employeeDocumentService;

    @Inject
    RecruitmentS3StorageService recruitmentS3StorageService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SlackService slackService;

    /**
     * Archive one completed case. Never throws — outcome lands on the
     * case row ({@code archive_status} / {@code archive_error}).
     *
     * @return true when the case reached ARCHIVED in this pass
     */
    public boolean archiveCompletedCase(SigningCase signingCase) {
        String caseKey = signingCase.getCaseKey();
        if (!"PENDING".equals(signingCase.getArchiveStatus())) {
            return "ARCHIVED".equals(signingCase.getArchiveStatus());
        }

        try {
            // Recruitment-flow? The dossier revision linkage is the marker
            // (the case's user_uuid is the SENDING user, not the signer).
            CandidateDossierRevision revision = CandidateDossierRevision
                    .find("signingCaseKey = ?1 ORDER BY createdAt DESC", caseKey)
                    .firstResult();
            if (revision != null) {
                return archiveRecruitmentCase(signingCase, revision);
            }
            return archiveEmployeeCase(signingCase);
        } catch (Exception e) {
            log.errorf(e, "S3 archival failed for case %s: %s", caseKey, e.getMessage());
            markArchiveError(signingCase, e.getMessage());
            return false;
        }
    }

    // ── Employee flow (spec §6.5.1) ────────────────────────────────────────

    private boolean archiveEmployeeCase(SigningCase signingCase) {
        String caseKey = signingCase.getCaseKey();
        String userUuid = signingCase.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            markArchiveError(signingCase, "Case has no user_uuid — cannot archive");
            return false;
        }

        List<SignedDocumentDownload> documents = signingService.downloadAllSignedDocuments(caseKey);
        if (documents.isEmpty()) {
            markArchiveError(signingCase, "No signed documents downloadable from NextSign");
            return false;
        }

        EmployeeDocumentCategory category = resolveCategory(signingCase);
        String timestamp = LocalDateTime.now().format(FILENAME_DATE_FORMATTER);

        int present = 0;
        List<String> failures = new ArrayList<>();
        for (SignedDocumentDownload doc : documents) {
            if (EmployeeDocument.findBySigningCase(caseKey, doc.index()) != null) {
                present++;
                continue;
            }
            String baseName = doc.name() != null && !doc.name().isBlank() ? doc.name() : "document_" + doc.index();
            // original_filename keeps the archival timestamp — it is what makes
            // repeat sends of the same document distinguishable. The display
            // name is built from the CLEAN base name instead, so the employee
            // reads "Lønregulering" and not "Lønregulering signed 2026 08 10
            // 143022": a machine-uniqueness suffix is not part of a title.
            String filename = stripPdfSuffix(baseName) + "_signed_" + timestamp + ".pdf";
            String displayName = EmployeeDocumentCategorizerRules.buildDisplayName(
                    category, stripPdfSuffix(baseName) + ".pdf", null, null);
            try {
                employeeDocumentService.store(new StoreCommand(
                        userUuid, doc.pdfBytes(), filename, displayName, "application/pdf",
                        category, null, EmployeeDocumentSource.SIGNING,
                        caseKey, doc.index(),
                        false, false, null, null, true));
                present++;
            } catch (RuntimeException e) {
                // A concurrent pass may have won the uq_ed_signing race —
                // re-check before counting it as a failure.
                if (EmployeeDocument.findBySigningCase(caseKey, doc.index()) != null) {
                    present++;
                } else {
                    log.errorf(e, "Archival store failed case=%s docIndex=%d", caseKey, doc.index());
                    failures.add(baseName);
                }
            }
        }

        if (present >= documents.size() && failures.isEmpty()) {
            markArchived(signingCase);
            notifyOwner(signingCase);
            log.infof("Archived all %d signed documents of case %s to the employee store",
                    documents.size(), caseKey);
            return true;
        }
        markArchiveError(signingCase, "Archived " + present + "/" + documents.size()
                + (failures.isEmpty() ? "" : "; failed: " + String.join(", ", failures)));
        return false;
    }

    // ── Recruitment flow (spec §6.5.2) ─────────────────────────────────────

    private boolean archiveRecruitmentCase(SigningCase signingCase, CandidateDossierRevision revision) {
        String caseKey = signingCase.getCaseKey();

        if (revision.getSignedPdfsSnapshot() != null && !revision.getSignedPdfsSnapshot().isBlank()) {
            // Already archived on a previous pass.
            markArchived(signingCase);
            return true;
        }

        CandidateDossier dossier = CandidateDossier.findById(revision.getDossierUuid());
        if (dossier == null || dossier.getCandidateUuid() == null) {
            markArchiveError(signingCase, "Dossier/candidate not resolvable for revision " + revision.getUuid());
            return false;
        }
        UUID candidateUuid = UUID.fromString(dossier.getCandidateUuid());

        List<SignedDocumentDownload> documents = signingService.downloadAllSignedDocuments(caseKey);
        if (documents.isEmpty()) {
            markArchiveError(signingCase, "No signed documents downloadable from NextSign");
            return false;
        }

        List<GeneratedPdfRef> refs = new ArrayList<>(documents.size());
        List<String> storedUuids = new ArrayList<>(documents.size());
        try {
            for (SignedDocumentDownload doc : documents) {
                String baseName = doc.name() != null && !doc.name().isBlank() ? doc.name() : "document_" + doc.index();
                String filename = stripPdfSuffix(baseName) + "_signed.pdf";
                String fileUuid = recruitmentS3StorageService.storeSignedDocument(
                        doc.pdfBytes(), filename, candidateUuid);
                storedUuids.add(fileUuid);
                refs.add(new GeneratedPdfRef(filename, fileUuid));
            }
            String snapshot = objectMapper.writeValueAsString(refs);
            writeSignedSnapshot(revision.getUuid(), snapshot);
            markArchived(signingCase);
            log.infof("Archived %d signed documents of recruitment case %s to candidate staging (candidate=%s)",
                    documents.size(), caseKey, candidateUuid);
            return true;
        } catch (Exception e) {
            // Compensate staged files so a retry does not accumulate
            // orphan duplicates in the candidate's staging space.
            for (String fileUuid : storedUuids) {
                try {
                    recruitmentS3StorageService.deleteGeneratedPdf(fileUuid);
                } catch (RuntimeException cleanup) {
                    log.warnf(cleanup, "Compensating staging delete failed fileUuid=%s (candidate GDPR engine will reap)", fileUuid);
                }
            }
            log.errorf(e, "Recruitment archival failed for case %s", caseKey);
            markArchiveError(signingCase, e.getMessage());
            return false;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Archival category resolution (V475 precedence): the sender's
     * explicit {@code archive_category} pick (upload-wizard cases) wins;
     * otherwise the template's {@code TemplateCategory} mapping; OTHER
     * when neither resolves. An unparseable stored value degrades to the
     * template mapping rather than failing the archival pass.
     */
    static EmployeeDocumentCategory resolveCategory(SigningCase signingCase) {
        String explicit = signingCase.getArchiveCategory();
        if (explicit != null && !explicit.isBlank()) {
            try {
                return EmployeeDocumentCategory.valueOf(explicit);
            } catch (IllegalArgumentException e) {
                log.warnf("Unknown archive_category '%s' on case %s — falling back to template mapping",
                        explicit, signingCase.getCaseKey());
            }
        }
        return mapTemplateCategory(signingCase.getTemplateUuid());
    }

    static EmployeeDocumentCategory mapTemplateCategory(String templateUuid) {
        if (templateUuid == null || templateUuid.isBlank()) return EmployeeDocumentCategory.OTHER;
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(templateUuid);
        if (template == null) return EmployeeDocumentCategory.OTHER;
        return EmployeeDocumentCategory.fromTemplateCategory(template.getCategory());
    }

    static String stripPdfSuffix(String name) {
        if (name == null) return "";
        return name.toLowerCase().endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
    }

    @Transactional
    void markArchived(SigningCase signingCase) {
        signingCase.setArchiveStatus("ARCHIVED");
        signingCase.setArchiveError(null);
        signingCase.setArchiveAttempts(0);
        signingCaseRepository.persist(signingCase);
    }

    /**
     * Record a failed archival attempt and, once the cap is reached, take the
     * case out of the sweep's reach.
     *
     * <p>The catch-up sweep's only state predicate is
     * {@code archive_status='PENDING'}, so leaving the row PENDING after a
     * failure — which is what this method used to do unconditionally — means
     * a case whose NextSign envelope has expired is re-attempted on every
     * 5-minute pass for ever. Since the sweep is capped at
     * {@code ARCHIVAL_SWEEP_LIMIT} rows ordered oldest-first, a few
     * permanently-stuck cases would eventually starve every newer case out of
     * the queue.</p>
     *
     * <p>At {@link #MAX_ARCHIVE_ATTEMPTS} the case moves to the existing
     * terminal state SKIPPED rather than a new enum value (spec §14 proposes
     * exactly this), keeping the last error so the reason stays legible in
     * the case dialog and the migration report. Transient failures are
     * generously accommodated: the cap is attempts, not wall-clock, and at
     * one attempt per 5-minute pass it spans roughly two hours before a case
     * is given up on.</p>
     */
    @Transactional
    void markArchiveError(SigningCase signingCase, String error) {
        String message = error == null ? "unknown" :
                (error.length() > 2000 ? error.substring(0, 2000) : error);
        int attempts = (signingCase.getArchiveAttempts() == null ? 0 : signingCase.getArchiveAttempts()) + 1;
        signingCase.setArchiveAttempts(attempts);
        if (attempts >= MAX_ARCHIVE_ATTEMPTS) {
            signingCase.setArchiveStatus("SKIPPED");
            signingCase.setArchiveError("Gave up after " + attempts + " archival attempts. Last error: " + message);
            log.warnf("S3 archival abandoned for case %s after %d attempts: %s",
                    signingCase.getCaseKey(), attempts, message);
        } else {
            signingCase.setArchiveError(message);
        }
        signingCaseRepository.persist(signingCase);
    }

    @Transactional
    void writeSignedSnapshot(String revisionUuid, String snapshotJson) {
        CandidateDossierRevision.update("signedPdfsSnapshot = ?1 WHERE uuid = ?2",
                snapshotJson, revisionUuid);
    }

    /** Best-effort Slack DM to the case owner. */
    private void notifyOwner(SigningCase signingCase) {
        try {
            String userUuid = signingCase.getUserUuid();
            if (userUuid == null || userUuid.isBlank()) return;
            Optional<User> userOpt = User.findByIdOptional(userUuid);
            if (userOpt.isEmpty()) return;
            User user = userOpt.get();
            if (user.getSlackusername() == null || user.getSlackusername().isBlank()) return;
            slackService.sendSignedDocumentNotification(
                    user, signingCase.getDocumentName(), signingCase.getUpdatedAt());
        } catch (Exception e) {
            log.warnf(e, "Slack notification failed for archived case %s (archival unaffected)",
                    signingCase.getCaseKey());
        }
    }
}
