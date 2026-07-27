package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.PromoteCommand;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.PromotionStatus;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The S3→S3 conversion promotion (employee-documents spec §6.5.3) — the
 * replacement for {@code SharePointEmployeeFolderService.copyToEmployeeFolder}
 * once the {@code employee_documents.writers.promotion} toggle is ON.
 *
 * <p>For each staged file of a hired candidate — generated dossier PDFs,
 * signed PDFs (from {@code signed_pdfs_snapshot}), appendices, and
 * candidate-flow onboarding ID documents — performs a server-side
 * {@code CopyObject} from {@code trustworksfiles} into the employee
 * bucket with a proper {@code employee_documents} row
 * ({@code source=PROMOTION}), then deletes the staging object + its
 * {@code files} row. Synchronous, sub-second, strongly consistent —
 * no PARTIAL state, no retention stamping, no reaper.</p>
 *
 * <p>Idempotent per file: every row carries
 * {@code migrated_from = files:{fileUuid}}, and
 * {@link EmployeeDocumentService#storeFromS3} skips sources that already
 * have a row — a re-driven FAILED promotion completes the remainder.</p>
 */
@JBossLog
@ApplicationScoped
public class S3EmployeePromotionService {

    @Inject
    EmployeeDocumentService employeeDocumentService;

    @Inject
    S3FileService s3FileService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentHrSlackNotifier recruitmentHrSlackNotifier;

    @ConfigProperty(name = "bucket.files")
    String stagingBucket;

    /** One staged file scheduled for promotion. */
    private record PromotionItem(
            String fileUuid,
            String filename,
            EmployeeDocumentCategory category,
            String label,
            String signingCaseKey,
            Integer documentIndex) { }

    /**
     * Promote every staged file of a hired candidate into the employee
     * store. Never throws — the outcome lands on
     * {@code recruitment_candidates.promotion_status} and is re-driven by
     * the nextsign-status-sync sweep. Safe to call from a
     * {@code ManagedExecutor} post-commit and from the sweep.
     */
    public void runPromotion(UUID candidateUuid) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null || candidate.getStatus() != CandidateStatus.HIRED) {
            log.warnf("runPromotion: candidate=%s not HIRED, skipping", candidateUuid);
            return;
        }
        if (candidate.getPromotionStatus() == PromotionStatus.COMPLETED) {
            log.debugf("runPromotion: candidate=%s already COMPLETED, skipping", candidateUuid);
            return;
        }
        String userUuid = candidate.getConvertedUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            log.warnf("runPromotion: candidate=%s has no converted user — leaving PENDING", candidateUuid);
            return;
        }

        List<PromotionItem> items = collectItems(candidate);
        int promoted = 0;
        int failed = 0;
        List<String> signedFilenames = new ArrayList<>();

        for (PromotionItem item : items) {
            try {
                employeeDocumentService.storeFromS3(new PromoteCommand(
                        userUuid,
                        stagingBucket,
                        item.fileUuid(),
                        item.filename(),
                        item.category(),
                        item.label(),
                        EmployeeDocumentSource.PROMOTION,
                        item.signingCaseKey(),
                        item.documentIndex(),
                        "files:" + item.fileUuid()));
                promoted++;
                if ("signed".equals(item.label())) {
                    signedFilenames.add(item.filename());
                }
                // Delete the staging original + files row. Idempotent — a
                // re-run whose store was skipped by provenance still clears
                // any leftover staging object.
                try {
                    s3FileService.delete(item.fileUuid());
                } catch (RuntimeException e) {
                    log.warnf(e, "Staging delete failed fileUuid=%s (promotion unaffected; retried next pass)",
                            item.fileUuid());
                }
            } catch (RuntimeException e) {
                log.errorf(e, "Promotion failed candidate=%s fileUuid=%s filename=%s",
                        candidateUuid, item.fileUuid(), item.filename());
                failed++;
            }
        }

        PromotionStatus status = failed == 0 ? PromotionStatus.COMPLETED : PromotionStatus.FAILED;
        applyPromotionResult(candidateUuid, status, signedFilenames);
        log.infof("S3 promotion candidate=%s user=%s items=%d promoted=%d failed=%d status=%s",
                candidateUuid, userUuid, items.size(), promoted, failed, status);
    }

    /**
     * Persist the outcome; on COMPLETED fire the HR Slack hire
     * notification (in-memory deduped per candidate inside the notifier).
     */
    @Transactional
    public void applyPromotionResult(UUID candidateUuid, PromotionStatus status, List<String> signedFilenames) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            log.warnf("applyPromotionResult: candidate=%s not found", candidateUuid);
            return;
        }
        candidate.setPromotionStatus(status);
        if (status == PromotionStatus.COMPLETED) {
            UUID recruiter = parseUuidOrNull(candidate.getCreatedByUseruuid());
            recruitmentHrSlackNotifier.notifyHire(candidate, recruiter, signedFilenames);
        }
    }

    // ── enumeration ────────────────────────────────────────────────────────

    private List<PromotionItem> collectItems(RecruitmentCandidate candidate) {
        List<PromotionItem> items = new ArrayList<>();
        Set<String> claimedSigningSlots = new HashSet<>();

        for (CandidateDossierRevision rev : CandidateDossierRevision.findByCandidate(candidate.getUuid())) {
            EmployeeDocumentCategory dossierCategory = resolveDossierCategory(rev.getDossierUuid());

            // Generated (unsigned) dossier PDFs → CONTRACT-ish, labelled
            // 'unsigned' when the revision went out for signature.
            for (GeneratedPdfRef ref : parseRefs(rev.getGeneratedPdfsSnapshot(), candidate.getUuid(), rev.getUuid())) {
                if (ref.fileUuid() == null) continue;
                items.add(new PromotionItem(ref.fileUuid(), ref.filename(), dossierCategory,
                        rev.getSigningCaseKey() != null ? "unsigned" : null, null, null));
            }

            // Signed PDFs archived at completion (§6.5.2) → linked to the
            // signing case so signing UIs can resolve them. Only the first
            // revision per case claims the (case_key, index) slots —
            // uq_ed_signing must never collide.
            List<GeneratedPdfRef> signedRefs = parseRefs(rev.getSignedPdfsSnapshot(), candidate.getUuid(), rev.getUuid());
            for (int i = 0; i < signedRefs.size(); i++) {
                GeneratedPdfRef ref = signedRefs.get(i);
                if (ref.fileUuid() == null) continue;
                String slot = rev.getSigningCaseKey() + "#" + i;
                boolean claimSlot = rev.getSigningCaseKey() != null && claimedSigningSlots.add(slot);
                items.add(new PromotionItem(ref.fileUuid(), ref.filename(), dossierCategory,
                        "signed",
                        claimSlot ? rev.getSigningCaseKey() : null,
                        claimSlot ? i : null));
            }
        }

        for (CandidateDossierAppendix appendix : CandidateDossierAppendix.findByCandidate(candidate.getUuid())) {
            if (appendix.getFileUuid() == null) continue;
            items.add(new PromotionItem(appendix.getFileUuid(), appendix.getOriginalFilename(),
                    EmployeeDocumentCategory.OTHER, null, null, null));
        }

        for (OnboardingUploadSubmission sub : OnboardingUploadSubmission.findS3SubmissionsByCandidate(candidate.getUuid())) {
            if (sub.getS3FileUuid() == null) continue;
            items.add(new PromotionItem(sub.getS3FileUuid(),
                    onboardingFilename(sub),
                    EmployeeDocumentCategory.IDENTITY, null, null, null));
        }

        return items;
    }

    private List<GeneratedPdfRef> parseRefs(String snapshotJson, String candidateUuid, String revisionUuid) {
        if (snapshotJson == null || snapshotJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(snapshotJson, new TypeReference<List<GeneratedPdfRef>>() { });
        } catch (Exception e) {
            log.errorf(e, "Could not parse PDF snapshot candidate=%s revision=%s — skipping its files",
                    candidateUuid, revisionUuid);
            return List.of();
        }
    }

    /** Dossier template category → employee-document category; OTHER when unresolvable. */
    private EmployeeDocumentCategory resolveDossierCategory(String dossierUuid) {
        if (dossierUuid == null) return EmployeeDocumentCategory.OTHER;
        CandidateDossier dossier = CandidateDossier.findById(dossierUuid);
        if (dossier == null || dossier.getTemplateUuid() == null) return EmployeeDocumentCategory.CONTRACT;
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(dossier.getTemplateUuid());
        if (template == null) return EmployeeDocumentCategory.CONTRACT;
        return EmployeeDocumentCategory.fromTemplateCategory(template.getCategory());
    }

    /** Deterministic identity-document filename mirroring the SharePoint layout. */
    private static String onboardingFilename(OnboardingUploadSubmission sub) {
        String original = sub.getOriginalFilename();
        if (original != null && !original.isBlank()) return original;
        String extension = "image/png".equalsIgnoreCase(sub.getContentType()) ? ".png" : ".jpg";
        return sub.getDocumentType().name().toLowerCase().replace('_', '-') + extension;
    }

    private static UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
