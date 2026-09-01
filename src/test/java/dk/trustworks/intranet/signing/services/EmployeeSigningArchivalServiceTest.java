package dk.trustworks.intranet.signing.services;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Category resolution for the S3 archival step (V475): the sender's
 * explicit {@code archive_category} wins; otherwise the template mapping.
 * The template-mapping leg itself needs a live {@code DocumentTemplateEntity}
 * lookup and is covered by the template-less (null → OTHER) case here; the
 * per-TemplateCategory mapping is covered by
 * {@code EmployeeDocumentCategory.fromTemplateCategory}'s own contract.
 *
 * <p>Also covers the archival retry cap (V551): the catch-up sweep selects
 * purely on {@code archive_status='PENDING'}, so the only thing standing
 * between an expired NextSign envelope and an unbounded 5-minute retry loop
 * is {@code markArchiveError}'s attempt counter.</p>
 *
 * <p>And the legacy guard of the SharePoint deletion release: with the
 * {@code sharepoint_upload_status} predicate gone from the catch-up sweep,
 * {@code legacyMigratedCopies} is what keeps a case whose signed PDFs
 * arrived through the SharePoint→S3 migration from being downloaded and
 * stored a second time.</p>
 */
class EmployeeSigningArchivalServiceTest {

    private static SigningCase caseWith(String archiveCategory, String templateUuid) {
        SigningCase signingCase = new SigningCase();
        signingCase.setCaseKey("case-1");
        signingCase.setArchiveCategory(archiveCategory);
        signingCase.setTemplateUuid(templateUuid);
        return signingCase;
    }

    @Test
    void explicitCategoryWins() {
        assertEquals(EmployeeDocumentCategory.SALARY,
                EmployeeSigningArchivalService.resolveCategory(caseWith("SALARY", null)));
    }

    @Test
    void everyEnumValueIsAccepted() {
        for (EmployeeDocumentCategory category : EmployeeDocumentCategory.values()) {
            assertEquals(category,
                    EmployeeSigningArchivalService.resolveCategory(caseWith(category.name(), null)));
        }
    }

    @Test
    void nullOrBlankFallsBackToTemplateMapping_templateLessIsOther() {
        assertEquals(EmployeeDocumentCategory.OTHER,
                EmployeeSigningArchivalService.resolveCategory(caseWith(null, null)));
        assertEquals(EmployeeDocumentCategory.OTHER,
                EmployeeSigningArchivalService.resolveCategory(caseWith("  ", null)));
    }

    @Test
    void unknownStoredValueDegradesToTemplateMapping() {
        assertEquals(EmployeeDocumentCategory.OTHER,
                EmployeeSigningArchivalService.resolveCategory(caseWith("NOT_A_CATEGORY", null)));
    }

    // ── Archival retry cap (V551, spec §14) ────────────────────────────────

    /**
     * A service wired with a stubbed repository — enough to exercise
     * {@code markArchiveError}, which is the whole retry-cap mechanism.
     * Same package, so the {@code @Inject} field is directly assignable.
     */
    private static EmployeeSigningArchivalService serviceWithStubRepository() {
        EmployeeSigningArchivalService service = new EmployeeSigningArchivalService();
        // Mocked: persist() would need a live EntityManager, and the retry
        // cap is pure field arithmetic on the entity.
        service.signingCaseRepository = mock(SigningCaseRepository.class);
        return service;
    }

    private static SigningCase pendingCase() {
        SigningCase signingCase = new SigningCase();
        signingCase.setCaseKey("case-retry");
        signingCase.setArchiveStatus("PENDING");
        signingCase.setArchiveAttempts(0);
        return signingCase;
    }

    @Test
    void eachFailureIncrementsTheAttemptCounterAndKeepsTheCasePending() {
        EmployeeSigningArchivalService service = serviceWithStubRepository();
        SigningCase signingCase = pendingCase();

        service.markArchiveError(signingCase, "NextSign 404");

        assertEquals(1, signingCase.getArchiveAttempts());
        assertEquals("PENDING", signingCase.getArchiveStatus(),
                "a single failure must stay retryable");
        assertEquals("NextSign 404", signingCase.getArchiveError());
    }

    @Test
    void reachingTheCapAbandonsTheCaseAsSkippedAndKeepsTheReason() {
        EmployeeSigningArchivalService service = serviceWithStubRepository();
        SigningCase signingCase = pendingCase();

        for (int i = 0; i < EmployeeSigningArchivalService.MAX_ARCHIVE_ATTEMPTS; i++) {
            service.markArchiveError(signingCase, "envelope expired");
        }

        assertEquals(EmployeeSigningArchivalService.MAX_ARCHIVE_ATTEMPTS,
                signingCase.getArchiveAttempts());
        assertEquals("SKIPPED", signingCase.getArchiveStatus(),
                "at the cap the case must leave the sweep's PENDING selection");
        assertTrue(signingCase.getArchiveError().contains("envelope expired"),
                "the last error must survive so the reason stays legible");
        assertTrue(signingCase.getArchiveError().contains("Gave up after"));
    }

    @Test
    void theCaseStaysPendingForEveryAttemptBeforeTheCap() {
        EmployeeSigningArchivalService service = serviceWithStubRepository();
        SigningCase signingCase = pendingCase();

        for (int i = 0; i < EmployeeSigningArchivalService.MAX_ARCHIVE_ATTEMPTS - 1; i++) {
            service.markArchiveError(signingCase, "transient S3 timeout");
            assertEquals("PENDING", signingCase.getArchiveStatus(),
                    "a transient outage must not be abandoned early (attempt " + (i + 1) + ")");
        }
    }

    @Test
    void aNullAttemptCounterFromALegacyRowIsTreatedAsZero() {
        EmployeeSigningArchivalService service = serviceWithStubRepository();
        SigningCase signingCase = pendingCase();
        signingCase.setArchiveAttempts(null);

        service.markArchiveError(signingCase, "boom");

        assertEquals(1, signingCase.getArchiveAttempts());
        assertEquals("PENDING", signingCase.getArchiveStatus());
    }

    @Test
    void aSuccessfulArchiveClearsTheCounterAndTheError() {
        EmployeeSigningArchivalService service = serviceWithStubRepository();
        SigningCase signingCase = pendingCase();
        service.markArchiveError(signingCase, "one bad pass");

        service.markArchived(signingCase);

        assertEquals("ARCHIVED", signingCase.getArchiveStatus());
        assertEquals(0, signingCase.getArchiveAttempts());
        assertNull(signingCase.getArchiveError());
    }

    @Test
    void aVeryLongErrorIsTruncatedBeforeItReachesTheColumn() {
        EmployeeSigningArchivalService service = serviceWithStubRepository();
        SigningCase signingCase = pendingCase();

        service.markArchiveError(signingCase, "x".repeat(5000));

        assertEquals(2000, signingCase.getArchiveError().length());
    }

    // ── Legacy SharePoint-upload guard (SharePoint deletion release) ───────

    private static EmployeeDocument migrated(String originalFilename) {
        EmployeeDocument doc = new EmployeeDocument();
        doc.setOriginalFilename(originalFilename);
        doc.setSource(EmployeeDocumentSource.MIGRATION);
        return doc;
    }

    private static SigningCase legacyCase(String documentName, LocalDateTime createdAt) {
        SigningCase signingCase = new SigningCase();
        signingCase.setCaseKey("case-legacy");
        signingCase.setDocumentName(documentName);
        signingCase.setCreatedAt(createdAt);
        signingCase.setArchiveStatus("PENDING");
        return signingCase;
    }

    @Test
    void aMigratedCopyStampedAfterTheCaseIsRecognisedAsItsOwnOutput() {
        SigningCase signingCase = legacyCase("Ansættelseskontrakt.pdf", LocalDateTime.of(2025, 3, 1, 9, 0));

        List<EmployeeDocument> copies = EmployeeSigningArchivalService.legacyMigratedCopies(signingCase, List.of(
                migrated("Ansættelseskontrakt_signed_2025-03-02_101500.pdf"),
                migrated("Lønregulering_signed_2025-03-02_101500.pdf")));

        assertEquals(1, copies.size(), "only the case's own document name counts");
        assertEquals("Ansættelseskontrakt_signed_2025-03-02_101500.pdf", copies.get(0).getOriginalFilename());
    }

    @Test
    void everyDocumentOfAMultiDocumentBatchIsRecognised() {
        SigningCase signingCase = legacyCase("Kontrakt", LocalDateTime.of(2025, 3, 1, 9, 0));

        List<EmployeeDocument> copies = EmployeeSigningArchivalService.legacyMigratedCopies(signingCase, List.of(
                migrated("Kontrakt_signed_2025-03-02_101500.pdf"),
                migrated("Kontrakt_signed_2025-03-09_080000.pdf")));

        assertEquals(2, copies.size());
    }

    @Test
    void aCopyStampedLongBeforeTheCaseBelongsToAnEarlierSigningOfTheSameName() {
        // Re-signing "Lønregulering" two years later must archive the NEW
        // PDF — the predecessor's migrated copy is not this case's output,
        // and treating it as such would silently drop the new document.
        SigningCase signingCase = legacyCase("Lønregulering", LocalDateTime.of(2026, 8, 10, 14, 0));

        List<EmployeeDocument> copies = EmployeeSigningArchivalService.legacyMigratedCopies(signingCase,
                List.of(migrated("Lønregulering_signed_2024-05-01_120000.pdf")));

        assertTrue(copies.isEmpty());
    }

    @Test
    void aZoneOffsetBetweenTheStampAndTheCaseIsTolerated() {
        // The stamp was the uploading container's local clock; created_at is
        // NextSign's. A document signed right after creation can carry a
        // stamp a few hours "before" it.
        SigningCase signingCase = legacyCase("Kontrakt", LocalDateTime.of(2025, 3, 1, 9, 0));

        List<EmployeeDocument> copies = EmployeeSigningArchivalService.legacyMigratedCopies(signingCase,
                List.of(migrated("Kontrakt_signed_2025-03-01_070000.pdf")));

        assertEquals(1, copies.size());
    }

    @Test
    void aNameWithoutAParseableStampIsNoEvidence() {
        SigningCase signingCase = legacyCase("Kontrakt", LocalDateTime.of(2025, 3, 1, 9, 0));

        assertTrue(EmployeeSigningArchivalService.legacyMigratedCopies(signingCase,
                List.of(migrated("Kontrakt_signed_final.pdf"))).isEmpty(),
                "matches the pattern but carries no stamp — do not guess");
        assertTrue(EmployeeSigningArchivalService.legacyMigratedCopies(signingCase,
                List.of(migrated("Kontrakt.pdf"))).isEmpty(),
                "not a signed copy at all");
    }

    @Test
    void aCaseWithoutACreationTimeAcceptsAnyStampedCopy() {
        SigningCase signingCase = legacyCase("Kontrakt", null);

        assertEquals(1, EmployeeSigningArchivalService.legacyMigratedCopies(signingCase,
                List.of(migrated("Kontrakt_signed_2019-01-01_000000.pdf"))).size());
    }

    @Test
    void aBlankDocumentNameOrNoMigratedDocumentsMatchesNothing() {
        assertTrue(EmployeeSigningArchivalService.legacyMigratedCopies(legacyCase("  ", null),
                List.of(migrated("x_signed_2025-01-01_000000.pdf"))).isEmpty());
        assertTrue(EmployeeSigningArchivalService.legacyMigratedCopies(legacyCase("Kontrakt", null),
                List.of()).isEmpty());
        assertTrue(EmployeeSigningArchivalService.legacyMigratedCopies(legacyCase("Kontrakt", null),
                null).isEmpty());
    }

    @Test
    void skippingForALegacyCopyIsTerminalAndDoesNotBurnAnAttempt() {
        EmployeeSigningArchivalService service = serviceWithStubRepository();
        SigningCase signingCase = pendingCase();
        signingCase.setArchiveAttempts(3);

        service.markSkipped(signingCase,
                "Legacy SharePoint upload; 2 migrated signed document(s) already in the S3 store");

        assertEquals("SKIPPED", signingCase.getArchiveStatus(),
                "must leave the sweep's PENDING selection for good");
        assertEquals(3, signingCase.getArchiveAttempts(), "a structural skip is not a failed attempt");
        assertTrue(signingCase.getArchiveError().startsWith("Legacy SharePoint upload"));
    }
}
