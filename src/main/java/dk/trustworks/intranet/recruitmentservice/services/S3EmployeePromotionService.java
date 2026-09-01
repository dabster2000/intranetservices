package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.maintenance.EmployeeDocumentCategorizerRules;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.PromoteCommand;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.PromotionStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The S3→S3 conversion promotion (employee-documents spec §6.5.3): the
 * hired candidate's signed documents, appendices and identity documents
 * move from recruitment staging into the new employee's document store.
 *
 * <h3>What promotes</h3>
 * <b>Only what came back signed.</b> That is the {@code signed_pdfs_snapshot}
 * of the latest revision per dossier whose signing case reached
 * {@code completed}, plus the candidate-flow onboarding identity documents
 * (never signature artefacts, but the employee's own papers).
 *
 * <p>Everything else stays in the offer dossier: generated drafts of every
 * revision, appendix originals, revisions whose signing case expired, was
 * denied, or is still pending, and the candidate's own {@code files}
 * attachments (CV, cover letter). A hire's negotiation history is not their
 * employee file.</p>
 *
 * <p>Appendix originals are excluded on evidence, not taste: across every
 * completed case in the corpus {@code signed == generated + signObligated
 * appendices}, i.e. a signature-obligated appendix comes back <em>inside</em>
 * the signed snapshot. Promoting the original too filed an unsigned twin
 * next to every signed annex.</p>
 *
 * <h3>What this never does</h3>
 * <b>It does not delete.</b> Promotion is a copy. Until 2026-08-11 the staging
 * original was deleted inside the copy loop, which turned every selection
 * mistake into permanent, unreviewable loss — a candidate promoted with the
 * old rule had all 24 of their dossier files destroyed. The offer dossier now
 * keeps every original, including the ones this pass declined to promote.
 * Note the deliberate consequence: promotion stamps no retention clock, so
 * {@code S3RetentionCleanupBatchlet} never reaps a promoted candidate's
 * staging files. A lifecycle for the offer dossier is separate, explicit work
 * — never a side effect of promotion.
 *
 * <p>Idempotent per file: every row carries
 * {@code migrated_from = files:{fileUuid}}, and
 * {@link EmployeeDocumentService#storeFromS3} skips sources that already
 * have a row — a re-driven FAILED promotion completes the remainder.</p>
 *
 * <h3>What the employee ends up seeing</h3>
 * <ul>
 *   <li><b>Named</b> through
 *       {@link MigrationCategorizerRules#buildDisplayName} — the same
 *       builder that named the migrated corpus, so a hire's contract
 *       reads {@code CONTRACT_ansættelseskontrakt.pdf} → "Ansættelseskontrakt"
 *       instead of keeping the raw dossier filename.</li>
 *   <li><b>The binding version only.</b> One row per signed document, so
 *       there is nothing to disambiguate against.</li>
 *   <li><b>No state in {@code label}.</b> Signed-ness lives in
 *       {@code signing_case_key}/{@code document_index}; {@code label}
 *       is a human title fallback in both UIs and must not carry
 *       {@code "signed"}/{@code "unsigned"}.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class S3EmployeePromotionService {

    /**
     * NextSign's terminal "the paperwork came back signed" status. Compared
     * case-insensitively — the column is {@code utf8mb4_general_ci} and the
     * rule must not depend on the collation to be right.
     */
    private static final String CASE_COMPLETED = "completed";

    @Inject
    EmployeeDocumentService employeeDocumentService;

    @Inject
    DossierCategoryResolver dossierCategoryResolver;

    @Inject
    SigningCaseRepository signingCaseRepository;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentHrSlackNotifier recruitmentHrSlackNotifier;

    @ConfigProperty(name = "bucket.files")
    String stagingBucket;

    // NOTE: no S3FileService. The absence is the guarantee — see the class
    // javadoc. Re-adding a file-deleting dependency here should not pass review.

    /**
     * One staged file scheduled for promotion.
     *
     * @param signed this is the signed artefact (drives the HR Slack hire
     *               notification). Signed-ness is <em>not</em> written to
     *               {@code label}: the store records it structurally via
     *               {@code signing_case_key}/{@code document_index}.
     * @param hrOnly withheld from the employee's own view. Now a property of
     *               the document class rather than of the revision that
     *               produced it: identity papers are HR-only, the binding
     *               contract is not. It previously encoded "this revision
     *               went out for signature", which made visibility track an
     *               invisible internal fact and read as random.
     */
    record PromotionItem(
            String fileUuid,
            String filename,
            EmployeeDocumentCategory category,
            String signingCaseKey,
            Integer documentIndex,
            boolean signed,
            boolean hrOnly) { }

    /**
     * What a promotion enumerates, plus the binding revisions it resolved and
     * a flag saying the run is simply too early.
     *
     * @param awaitingArchival a signing case reached {@code completed} but its
     *                         {@code signed_pdfs_snapshot} has not been
     *                         written yet (archival lag). Promoting nothing
     *                         and calling it COMPLETED would lose the
     *                         contract silently.
     */
    record Selection(List<PromotionItem> items,
                     List<CandidateDossierRevision> bindingRevisions,
                     boolean awaitingArchival) { }

    /**
     * Promote every signed artefact of a hired candidate into the employee
     * store. Never throws — the outcome lands on
     * {@code recruitment_candidates.promotion_status} and is re-driven by
     * the nextsign-status-sync sweep. Safe to call from a
     * {@code ManagedExecutor} post-commit and from the sweep.
     */
    public void runPromotion(UUID candidateUuid) {
        runPromotion(candidateUuid, false);
    }

    /**
     * @param force run even when the candidate is already COMPLETED. The
     *              escape hatch that lets a corrected selection rule be
     *              re-applied to an already-promoted hire without
     *              hand-editing {@code promotion_status} in production.
     *              Still idempotent per file, and still never deletes.
     */
    public void runPromotion(UUID candidateUuid, boolean force) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null || candidate.getStatus() != CandidateStatus.HIRED) {
            log.warnf("runPromotion: candidate=%s not HIRED, skipping", candidateUuid);
            return;
        }
        if (!force && candidate.getPromotionStatus() == PromotionStatus.COMPLETED) {
            log.debugf("runPromotion: candidate=%s already COMPLETED, skipping", candidateUuid);
            return;
        }
        String userUuid = candidate.getConvertedUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            log.warnf("runPromotion: candidate=%s has no converted user — leaving PENDING", candidateUuid);
            return;
        }

        Selection selection = collectItems(candidate);
        int stored = 0;
        int failed = 0;
        List<String> signedFilenames = new ArrayList<>();

        for (PromotionItem item : selection.items()) {
            String provenance = "files:" + item.fileUuid();
            // Asked before the store so the pass can tell "wrote 6" from
            // "re-drive found all 6 already there" — the difference between
            // announcing a hire on Slack and staying quiet.
            boolean alreadyPromoted = EmployeeDocument.findByProvenance(provenance) != null;
            try {
                employeeDocumentService.storeFromS3(new PromoteCommand(
                        userUuid,
                        stagingBucket,
                        item.fileUuid(),
                        item.filename(),
                        // Same builder the migration corpus was named with, so a
                        // hire's contract reads "Ansættelseskontrakt" next to the
                        // migrated ones rather than keeping its dossier filename.
                        EmployeeDocumentCategorizerRules.buildDisplayName(
                                item.category(), item.filename(), null, null),
                        item.category(),
                        null,
                        EmployeeDocumentSource.PROMOTION,
                        item.signingCaseKey(),
                        item.documentIndex(),
                        item.hrOnly(),
                        provenance));
                if (!alreadyPromoted) {
                    stored++;
                }
                if (item.signed()) {
                    signedFilenames.add(item.filename());
                }
            } catch (RuntimeException e) {
                log.errorf(e, "Promotion failed candidate=%s fileUuid=%s filename=%s",
                        candidateUuid, item.fileUuid(), item.filename());
                failed++;
            }
            // The staging original is deliberately left alone. See class javadoc.
        }

        if (failed == 0 && selection.awaitingArchival()) {
            // A case completed but its signed snapshot has not landed yet.
            // Leave the status untouched so the sweep retries. NOT FAILED:
            // nothing failed, and FAILED misreads on any HR dashboard.
            log.warnf("Promotion deferred candidate=%s — a completed case has no signed snapshot yet "
                    + "(items=%d stored=%d)", candidateUuid, selection.items().size(), stored);
            return;
        }

        PromotionStatus status;
        if (failed > 0) {
            status = PromotionStatus.FAILED;
        } else if (selection.bindingRevisions().isEmpty()) {
            status = PromotionStatus.NO_BINDING_DOCUMENTS;
        } else {
            status = PromotionStatus.COMPLETED;
        }
        applyPromotionResult(candidateUuid, status, signedFilenames, stored);
        log.infof("S3 promotion candidate=%s user=%s items=%d stored=%d failed=%d status=%s",
                candidateUuid, userUuid, selection.items().size(), stored, failed, status);
    }

    /**
     * Persist the outcome; fire the HR Slack notification.
     *
     * <p>Self-invoked from {@link #runPromotion} — safe, because ArC applies
     * interceptors by subclassing rather than delegation, so {@code this} is
     * the intercepted instance.</p>
     *
     * @param storedThisPass documents actually written by this pass. Zero on a
     *                       re-drive of an already-complete promotion, and the
     *                       notifier's dedup set is JVM-lifetime — so any
     *                       deploy between the original run and a re-drive
     *                       would otherwise re-announce the hire.
     */
    @Transactional
    public void applyPromotionResult(UUID candidateUuid, PromotionStatus status,
                                     List<String> signedFilenames, int storedThisPass) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            log.warnf("applyPromotionResult: candidate=%s not found", candidateUuid);
            return;
        }
        candidate.setPromotionStatus(status);
        if (storedThisPass == 0) {
            log.debugf("applyPromotionResult: candidate=%s stored nothing this pass — no HR notification",
                    candidateUuid);
            return;
        }
        UUID recruiter = parseUuidOrNull(candidate.getCreatedByUseruuid());
        if (status == PromotionStatus.COMPLETED) {
            recruitmentHrSlackNotifier.notifyHire(candidate, recruiter, signedFilenames);
        } else if (status == PromotionStatus.NO_BINDING_DOCUMENTS) {
            recruitmentHrSlackNotifier.notifyHireWithoutSignedContract(candidate, recruiter);
        }
    }

    // ── enumeration ────────────────────────────────────────────────────────

    /**
     * Enumerate what promotes. One rule: <b>a document promotes only if it
     * came back signed.</b>
     *
     * <p>Per dossier, the latest revision whose signing case reached
     * {@code completed} wins, and only its {@code signed_pdfs_snapshot} is
     * taken. Onboarding identity documents ride along because they are the
     * employee's own papers, not signature artefacts.</p>
     *
     * <p>Deliberately NOT enumerated: generated drafts of any revision,
     * appendix originals, non-completed cases, and the candidate's own
     * {@code files} attachments. See the class javadoc for why each.</p>
     */
    Selection collectItems(RecruitmentCandidate candidate) {
        List<PromotionItem> items = new ArrayList<>();
        List<CandidateDossierRevision> binding = new ArrayList<>();
        boolean awaitingArchival = false;

        // findByCandidate has no ORDER BY, so "latest" is decided here rather
        // than being inherited from whatever order the database returns.
        Map<String, List<CandidateDossierRevision>> byDossier =
                CandidateDossierRevision.findByCandidate(candidate.getUuid()).stream()
                        .collect(Collectors.groupingBy(CandidateDossierRevision::getDossierUuid));

        for (Map.Entry<String, List<CandidateDossierRevision>> entry : byDossier.entrySet()) {
            // Explicit type witness: chaining thenComparingInt off a bare
            // Comparator.comparing(...) breaks target-type inference.
            Optional<CandidateDossierRevision> latest = entry.getValue().stream()
                    .filter(r -> r.getKind() == RevisionKind.SIGNATURE)
                    .filter(r -> isCompletedSigningCase(r.getSigningCaseKey()))
                    .max(Comparator.<CandidateDossierRevision, LocalDateTime>comparing(
                                    CandidateDossierRevision::getCreatedAt)
                            .thenComparingInt(CandidateDossierRevision::getVersionNumber));
            if (latest.isEmpty()) continue;

            CandidateDossierRevision rev = latest.get();
            List<GeneratedPdfRef> signedRefs =
                    parseRefs(rev.getSignedPdfsSnapshot(), candidate.getUuid(), rev.getUuid());
            if (signedRefs.isEmpty()) {
                log.errorf("Promotion: candidate=%s revision=%s case=%s is completed but carries no "
                                + "signed snapshot — deferring to the archival sweep",
                        candidate.getUuid(), rev.getUuid(), rev.getSigningCaseKey());
                awaitingArchival = true;
                continue;
            }
            binding.add(rev);
            items.addAll(signedItems(rev, dossierCategoryResolver.resolve(rev.getDossierUuid()), signedRefs));
        }

        for (OnboardingUploadSubmission sub :
                OnboardingUploadSubmission.findS3SubmissionsByCandidate(candidate.getUuid())) {
            if (sub.getS3FileUuid() == null) continue;
            // hrOnly=true mirrors the user-flow onboarding writer: a driver's
            // licence, sundhedskort or straffeattest is HR-only by nature. The
            // two onboarding paths must not disagree about the same passport.
            items.add(new PromotionItem(sub.getS3FileUuid(),
                    onboardingFilename(sub),
                    EmployeeDocumentCategory.IDENTITY, null, null, false, true));
        }

        return new Selection(items, binding, awaitingArchival);
    }

    /**
     * The signed artefacts of one revision, positionally linked to their
     * signing case so signing UIs can resolve them.
     *
     * <p>{@code signing_cases.case_key} is UNIQUE and only one revision per
     * dossier is ever promoted, so {@code uq_ed_signing (signing_case_key,
     * document_index)} cannot be contended — the old first-revision-wins slot
     * claim is gone with the revisions that needed it. A null fileUuid is
     * skipped <em>without</em> shifting the remaining indexes, which must keep
     * matching NextSign's document order.</p>
     *
     * <p>Pure — no Panache, no I/O. This is where the rule is unit-tested.</p>
     */
    static List<PromotionItem> signedItems(CandidateDossierRevision rev,
                                           EmployeeDocumentCategory category,
                                           List<GeneratedPdfRef> signedRefs) {
        List<PromotionItem> items = new ArrayList<>(signedRefs.size());
        for (int i = 0; i < signedRefs.size(); i++) {
            GeneratedPdfRef ref = signedRefs.get(i);
            if (ref.fileUuid() == null) continue;
            items.add(new PromotionItem(ref.fileUuid(), ref.filename(), category,
                    rev.getSigningCaseKey(), i, true, false));
        }
        return items;
    }

    /**
     * Did this case actually come back signed? A non-null
     * {@code signing_case_key} only means the dossier went <em>out</em> for
     * signature — equally true of the abandoned attempts that typically
     * precede a hire (a hire's dossier commonly carries a {@code pending} and
     * an {@code expired} case alongside the {@code completed} one).
     */
    private boolean isCompletedSigningCase(String caseKey) {
        if (caseKey == null || caseKey.isBlank()) return false;
        return signingCaseRepository.findByCaseKey(caseKey)
                .map(sc -> CASE_COMPLETED.equalsIgnoreCase(sc.getStatus()))
                .orElseGet(() -> {
                    log.warnf("Promotion: revision references unknown signing case %s — treating as unsigned",
                            caseKey);
                    return false;
                });
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

    /** Deterministic identity-document filename (the original upload name, else a typed fallback). */
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
