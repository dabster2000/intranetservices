package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import dk.trustworks.intranet.recruitmentservice.services.S3EmployeePromotionService.PromotionItem;
import dk.trustworks.intranet.recruitmentservice.services.S3EmployeePromotionService.Selection;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The selection rule: <b>a document promotes only if it came back signed.</b>
 *
 * <p>Written against the production shape that exposed the defect. Converting
 * one candidate on 2026-08-11 filed 24 documents into their employee record —
 * five renders of the same contract from every revision the dossier had ever
 * produced, plus unsigned twins of every signed annex — because the enumerator
 * walked every revision and every snapshot without ever asking how the
 * signature attempt ended. Six of the 24 were the binding set.</p>
 *
 * <p>No {@code @QuarkusTest}: this is the DB-free tier, which is the deploy
 * gate. That is why {@code resolveDossierCategory} lives in its own injectable
 * bean — {@code findById} is inherited from {@code PanacheEntityBase}, so
 * {@code mockStatic(CandidateDossier.class)} would intercept nothing.</p>
 */
class S3EmployeePromotionSelectionTest {

    private static final String CANDIDATE = "8d0e1302-16cf-4b84-ade8-34d715cf3c34";
    private static final String DOSSIER = "03f122c2-0f3a-4e7c-826a-9a424d3e5cdc";
    private static final String COMPLETED_CASE = "6a69d83b61b7f3220480cc66";
    private static final String PENDING_CASE = "6a63c19a17089b02efec5576";
    private static final String EXPIRED_CASE = "6a63cfdc62f0e8ad34cea772";

    private S3EmployeePromotionService service;
    private SigningCaseRepository signingCaseRepository;

    @BeforeEach
    void setUp() {
        service = new S3EmployeePromotionService();
        signingCaseRepository = mock(SigningCaseRepository.class);
        DossierCategoryResolver categoryResolver = mock(DossierCategoryResolver.class);
        when(categoryResolver.resolve(any())).thenReturn(EmployeeDocumentCategory.CONTRACT);
        service.signingCaseRepository = signingCaseRepository;
        service.dossierCategoryResolver = categoryResolver;
        service.objectMapper = new ObjectMapper();
        // Default: an unstubbed case key is unknown, never completed.
        when(signingCaseRepository.findByCaseKey(anyString())).thenReturn(Optional.empty());
    }

    // ── signedItems: pure, no mocks ────────────────────────────────────────

    @Test
    void signedItemsLinkEachRefToItsCaseKeyAndPosition() {
        List<PromotionItem> items = S3EmployeePromotionService.signedItems(
                revision(1, RevisionKind.SIGNATURE, COMPLETED_CASE, null),
                EmployeeDocumentCategory.CONTRACT,
                refList("a.pdf", "u1", "b.pdf", "u2", "c.pdf", "u3"));

        assertEquals(List.of(0, 1, 2), items.stream().map(PromotionItem::documentIndex).toList());
        assertTrue(items.stream().allMatch(i -> COMPLETED_CASE.equals(i.signingCaseKey())));
    }

    @Test
    void aNullFileUuidIsSkippedWithoutShiftingTheRemainingIndexes() {
        // The index must keep matching NextSign's document order, so a hole
        // stays a hole rather than sliding the documents after it up one.
        List<PromotionItem> items = S3EmployeePromotionService.signedItems(
                revision(1, RevisionKind.SIGNATURE, COMPLETED_CASE, null),
                EmployeeDocumentCategory.CONTRACT,
                refList("a.pdf", "u1", "b.pdf", null, "c.pdf", "u3"));

        assertEquals(List.of(0, 2), items.stream().map(PromotionItem::documentIndex).toList());
    }

    @Test
    void signedItemsAreSignedEmployeeVisibleAndCarryTheDossierCategory() {
        List<PromotionItem> items = S3EmployeePromotionService.signedItems(
                revision(1, RevisionKind.SIGNATURE, COMPLETED_CASE, null),
                EmployeeDocumentCategory.CONTRACT,
                refList("a.pdf", "u1"));

        PromotionItem item = items.get(0);
        assertTrue(item.signed());
        assertFalse(item.hrOnly(), "the binding contract is the employee's own document");
        assertEquals(EmployeeDocumentCategory.CONTRACT, item.category());
    }

    // ── collectItems: the rule ─────────────────────────────────────────────

    /**
     * The regression test. Henrik Falch Midtgaard's exact production dossier:
     * two review revisions that never went out for signature, a pending
     * attempt, an expired attempt, and one completed case carrying six signed
     * PDFs. The old enumerator produced 24 items from this. The rule produces
     * six.
     */
    @Test
    void henriksShapeYieldsOnlyTheSignedSetOfTheCompletedCase() {
        caseStatus(PENDING_CASE, "pending");
        caseStatus(EXPIRED_CASE, "expired");
        caseStatus(COMPLETED_CASE, "completed");

        List<CandidateDossierRevision> revisions = List.of(
                revision(1, RevisionKind.REVIEW_PDF, null, null),
                revision(2, RevisionKind.REVIEW_PDF, null, null),
                revision(3, RevisionKind.SIGNATURE, PENDING_CASE, null),
                revision(4, RevisionKind.SIGNATURE, EXPIRED_CASE, null),
                revision(5, RevisionKind.SIGNATURE, COMPLETED_CASE, refs(
                        "Tillaeg_garantibonus_Henrik_Falch_Midtgaard_signed.pdf", "s0",
                        "100-dages-plan_signed.pdf", "s1",
                        "Ansættelseskontrakt_signed.pdf", "s2",
                        "Tillæg - Ansættelsesaftale Associate Partner v2_signed.pdf", "s3",
                        "Tillæg - Konkurrence- og Kundeklausul_signed.pdf", "s4",
                        "Bilag til partner ansættelseskontrakt  TW_SalgsBonusModel_v2_signed.pdf", "s5")));

        Selection selection = select(revisions, List.of());

        assertEquals(6, selection.items().size(),
                "only the completed case's signed set promotes");
        assertEquals(Set.of("s0", "s1", "s2", "s3", "s4", "s5"),
                selection.items().stream().map(PromotionItem::fileUuid).collect(Collectors.toSet()));
        assertTrue(selection.items().stream().allMatch(i -> COMPLETED_CASE.equals(i.signingCaseKey())));
        assertTrue(selection.items().stream().noneMatch(PromotionItem::hrOnly));
        assertFalse(selection.awaitingArchival());
        assertEquals(1, selection.bindingRevisions().size());
    }

    @Test
    void generatedDraftsNeverPromoteEvenFromTheCompletedRevision() {
        caseStatus(COMPLETED_CASE, "completed");
        CandidateDossierRevision rev = revision(5, RevisionKind.SIGNATURE, COMPLETED_CASE,
                refs("Ansættelseskontrakt_signed.pdf", "signed-1"));
        // The draft that went out for signature lives in the same revision.
        rev.setGeneratedPdfsSnapshot(refs("Ansættelseskontrakt.pdf", "draft-1"));

        Selection selection = select(List.of(rev), List.of());

        assertEquals(List.of("signed-1"), selection.items().stream().map(PromotionItem::fileUuid).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "in_progress", "expired", "denied", "rejected", "cancelled"})
    void aCaseThatDidNotCompletePromotesNothing(String status) {
        caseStatus(COMPLETED_CASE, status);

        Selection selection = select(
                List.of(revision(1, RevisionKind.SIGNATURE, COMPLETED_CASE, refs("a.pdf", "u1"))),
                List.of());

        assertTrue(selection.items().isEmpty(), status + " must not promote");
        assertTrue(selection.bindingRevisions().isEmpty());
        assertFalse(selection.awaitingArchival(), status + " is not a deferral — nothing is coming");
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "COMPLETED", "Completed"})
    void completedIsMatchedCaseInsensitively(String status) {
        // The column is utf8mb4_general_ci; the rule must not depend on the
        // collation to be right.
        caseStatus(COMPLETED_CASE, status);

        Selection selection = select(
                List.of(revision(1, RevisionKind.SIGNATURE, COMPLETED_CASE, refs("a.pdf", "u1"))),
                List.of());

        assertEquals(1, selection.items().size());
    }

    @Test
    void aReviewRevisionNeverPromotesEvenIfItSomehowCarriesSignedPdfs() {
        Selection selection = select(
                List.of(revision(1, RevisionKind.REVIEW_PDF, null, refs("a.pdf", "u1"))),
                List.of());

        assertTrue(selection.items().isEmpty());
    }

    @Test
    void anUnknownSigningCaseIsTreatedAsUnsignedRatherThanThrowing() {
        // findByCaseKey → empty, per the default stub.
        Selection selection = select(
                List.of(revision(1, RevisionKind.SIGNATURE, "no-such-case", refs("a.pdf", "u1"))),
                List.of());

        assertTrue(selection.items().isEmpty());
    }

    @Test
    void aCompletedCaseWithNoSignedSnapshotDefersInsteadOfReportingSuccess() {
        // Archival lag: the case completed but its signed PDFs have not been
        // written yet. Promoting nothing and calling it COMPLETED would lose
        // the contract silently.
        caseStatus(COMPLETED_CASE, "completed");

        Selection selection = select(
                List.of(revision(1, RevisionKind.SIGNATURE, COMPLETED_CASE, null)),
                List.of());

        assertTrue(selection.items().isEmpty());
        assertTrue(selection.awaitingArchival());
        assertTrue(selection.bindingRevisions().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "   ", "{not json"})
    void anEmptyOrUnreadableSignedSnapshotAlsoDefers(String snapshot) {
        caseStatus(COMPLETED_CASE, "completed");

        Selection selection = select(
                List.of(revision(1, RevisionKind.SIGNATURE, COMPLETED_CASE, snapshot)),
                List.of());

        assertTrue(selection.items().isEmpty());
        assertTrue(selection.awaitingArchival());
    }

    @Test
    void whenTwoRevisionsOfOneDossierCompletedTheLatestWins() {
        // Re-signing after a correction leaves two completed cases. Filing
        // both would put two binding contracts in one employee record.
        caseStatus(PENDING_CASE, "completed");
        caseStatus(COMPLETED_CASE, "completed");

        Selection selection = select(List.of(
                revision(3, RevisionKind.SIGNATURE, PENDING_CASE, refs("old.pdf", "old-1")),
                revision(5, RevisionKind.SIGNATURE, COMPLETED_CASE, refs("new.pdf", "new-1"))),
                List.of());

        assertEquals(List.of("new-1"), selection.items().stream().map(PromotionItem::fileUuid).toList());
        assertEquals(1, selection.bindingRevisions().size());
    }

    @Test
    void theSelectionDoesNotDependOnTheOrderTheDatabaseReturnsRevisionsIn() {
        // findByCandidate has no ORDER BY.
        caseStatus(PENDING_CASE, "completed");
        caseStatus(COMPLETED_CASE, "completed");
        List<CandidateDossierRevision> revisions = new ArrayList<>(List.of(
                revision(3, RevisionKind.SIGNATURE, PENDING_CASE, refs("old.pdf", "old-1")),
                revision(5, RevisionKind.SIGNATURE, COMPLETED_CASE, refs("new.pdf", "new-1"))));

        List<String> expected = List.of("new-1");
        for (int i = 0; i < 8; i++) {
            Collections.shuffle(revisions);
            assertIterableEquals(expected,
                    select(revisions, List.of()).items().stream().map(PromotionItem::fileUuid).toList(),
                    "revision order changed the plan");
        }
    }

    @Test
    void twoDossiersThatBothCompletedBothPromote() {
        caseStatus(PENDING_CASE, "completed");
        caseStatus(COMPLETED_CASE, "completed");
        CandidateDossierRevision other = revision(1, RevisionKind.SIGNATURE, PENDING_CASE, refs("o.pdf", "o-1"));
        other.setDossierUuid("a-second-dossier");

        Selection selection = select(
                List.of(revision(1, RevisionKind.SIGNATURE, COMPLETED_CASE, refs("a.pdf", "a-1")), other),
                List.of());

        assertEquals(Set.of("a-1", "o-1"),
                selection.items().stream().map(PromotionItem::fileUuid).collect(Collectors.toSet()));
        assertEquals(2, selection.bindingRevisions().size());
    }

    @Test
    void aHireWithNoSignedDocumentAtAllPromotesNothingAndIsNotADeferral() {
        // A contract signed on paper. Distinguished from archival lag, because
        // one resolves itself and the other needs a human.
        caseStatus(EXPIRED_CASE, "expired");

        Selection selection = select(
                List.of(revision(1, RevisionKind.SIGNATURE, EXPIRED_CASE, null)),
                List.of());

        assertTrue(selection.items().isEmpty());
        assertTrue(selection.bindingRevisions().isEmpty());
        assertFalse(selection.awaitingArchival());
    }

    // ── onboarding identity documents ──────────────────────────────────────

    @Test
    void onboardingIdentityDocumentsPromoteAsHrOnly() {
        // A driver's licence or sundhedskort is HR-only by nature. The
        // user-flow onboarding writer already files them that way; the two
        // paths must not disagree about the same passport.
        OnboardingUploadSubmission submission = new OnboardingUploadSubmission();
        submission.setS3FileUuid("id-1");
        submission.setOriginalFilename("kørekort.jpg");

        Selection selection = select(List.of(), List.of(submission));

        assertEquals(1, selection.items().size());
        PromotionItem item = selection.items().get(0);
        assertEquals(EmployeeDocumentCategory.IDENTITY, item.category());
        assertTrue(item.hrOnly(), "identity papers must not appear in the employee's own view");
        assertFalse(item.signed());
        assertEquals(null, item.signingCaseKey());
    }

    @Test
    void anOnboardingSubmissionWithoutAFileIsSkipped() {
        OnboardingUploadSubmission submission = new OnboardingUploadSubmission();
        submission.setS3FileUuid(null);

        assertTrue(select(List.of(), List.of(submission)).items().isEmpty());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Run {@code collectItems} with the two Panache finders stubbed. Both are
     * declared on the entity subclasses, so {@code mockStatic} intercepts them
     * — unlike the inherited {@code findById}, which is why the category
     * resolver is a separate bean.
     */
    private Selection select(List<CandidateDossierRevision> revisions,
                             List<OnboardingUploadSubmission> submissions) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(CANDIDATE);
        try (MockedStatic<CandidateDossierRevision> revs = mockStatic(CandidateDossierRevision.class);
             MockedStatic<OnboardingUploadSubmission> subs = mockStatic(OnboardingUploadSubmission.class)) {
            revs.when(() -> CandidateDossierRevision.findByCandidate(CANDIDATE)).thenReturn(revisions);
            subs.when(() -> OnboardingUploadSubmission.findS3SubmissionsByCandidate(CANDIDATE))
                    .thenReturn(submissions);
            return service.collectItems(candidate);
        }
    }

    private void caseStatus(String caseKey, String status) {
        SigningCase signingCase = new SigningCase();
        signingCase.setCaseKey(caseKey);
        signingCase.setStatus(status);
        when(signingCaseRepository.findByCaseKey(caseKey)).thenReturn(Optional.of(signingCase));
    }

    private static CandidateDossierRevision revision(int version, RevisionKind kind,
                                                     String caseKey, String signedSnapshot) {
        CandidateDossierRevision rev = new CandidateDossierRevision();
        rev.setUuid("rev-" + version);
        rev.setDossierUuid(DOSSIER);
        rev.setVersionNumber(version);
        rev.setKind(kind);
        rev.setSigningCaseKey(caseKey);
        rev.setSignedPdfsSnapshot(signedSnapshot);
        rev.setCreatedAt(LocalDateTime.of(2026, 7, 24, 0, 0).plusHours(version));
        return rev;
    }

    /** {@code refList("a.pdf", "uuid-a", ...)} → the parsed refs {@code signedItems} takes. */
    private static List<GeneratedPdfRef> refList(String... filenameThenFileUuid) {
        List<GeneratedPdfRef> refs = new ArrayList<>();
        for (int i = 0; i < filenameThenFileUuid.length; i += 2) {
            refs.add(new GeneratedPdfRef(filenameThenFileUuid[i], filenameThenFileUuid[i + 1]));
        }
        return refs;
    }

    /** {@code refs("a.pdf", "uuid-a", ...)} → the snapshot JSON the columns hold. */
    private static String refs(String... filenameThenFileUuid) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < filenameThenFileUuid.length; i += 2) {
            if (i > 0) sb.append(", ");
            String fileUuid = filenameThenFileUuid[i + 1];
            sb.append("{\"filename\": \"").append(filenameThenFileUuid[i]).append("\", \"fileUuid\": ")
                    .append(fileUuid == null ? "null" : "\"" + fileUuid + "\"").append('}');
        }
        return sb.append(']').toString();
    }
}
