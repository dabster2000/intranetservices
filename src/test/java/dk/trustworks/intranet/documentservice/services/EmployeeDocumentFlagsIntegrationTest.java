package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.EmployeeDocumentAudit;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentAuditAction;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.BulkFlagsSummary;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.PatchCommand;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bulk HR-only / archived flipping and display-name patching against the
 * real DB — the two mutations the HR Documents tab drives.
 */
@QuarkusTest
class EmployeeDocumentFlagsIntegrationTest {

    @Inject
    EmployeeDocumentService service;

    private final String userUuid = UUID.randomUUID().toString();
    private final String actorUuid = UUID.randomUUID().toString();

    @BeforeEach
    void arrange() {
        cleanup();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocumentAudit.delete("userUuid", userUuid);
            EmployeeDocument.delete("userUuid", userUuid);
        });
    }

    // ── Bulk flags ─────────────────────────────────────────────────────────

    @Test
    void bulkMarkHrOnlyFlipsEveryDocumentAndAuditsEachOne() {
        String a = persistDoc("a.pdf");
        String b = persistDoc("b.pdf");
        String c = persistDoc("c.pdf");

        BulkFlagsSummary summary = service.updateFlags(List.of(a, b, c), true, null, actorUuid);

        assertEquals(3, summary.requested());
        assertEquals(3, summary.updated());
        assertEquals(0, summary.notFound());
        assertTrue(load(a).isHrOnly());
        assertTrue(load(b).isHrOnly());
        assertTrue(load(c).isHrOnly());
        assertEquals(3, auditCount(EmployeeDocumentAuditAction.UPDATE),
                "one audit row per document, exactly as N single PATCHes would write");
    }

    @Test
    void bulkRemoveHrOnlyFlipsBack() {
        String a = persistDoc("a.pdf");
        String b = persistDoc("b.pdf");
        service.updateFlags(List.of(a, b), true, null, actorUuid);

        service.updateFlags(List.of(a, b), false, null, actorUuid);

        assertFalse(load(a).isHrOnly());
        assertFalse(load(b).isHrOnly());
    }

    @Test
    void bulkArchiveWritesAnArchiveAuditRowOnTheTransitionOnly() {
        String a = persistDoc("a.pdf");

        service.updateFlags(List.of(a), null, true, actorUuid);
        assertTrue(load(a).isArchived());
        assertEquals(1, auditCount(EmployeeDocumentAuditAction.ARCHIVE));

        // Re-archiving an archived document is an UPDATE, not a new ARCHIVE.
        service.updateFlags(List.of(a), null, true, actorUuid);
        assertEquals(1, auditCount(EmployeeDocumentAuditAction.ARCHIVE));
        assertEquals(1, auditCount(EmployeeDocumentAuditAction.UPDATE));
    }

    @Test
    void bulkLeavesUnsetFlagsAlone() {
        String a = persistDoc("a.pdf");
        service.updateFlags(List.of(a), true, true, actorUuid);

        service.updateFlags(List.of(a), false, null, actorUuid);

        assertFalse(load(a).isHrOnly());
        assertTrue(load(a).isArchived(), "archived was not in the command — untouched");
    }

    @Test
    void bulkReportsMissingDocumentsInsteadOfFailingTheBatch() {
        String a = persistDoc("a.pdf");
        String ghost = UUID.randomUUID().toString();

        BulkFlagsSummary summary = service.updateFlags(List.of(a, ghost), true, null, actorUuid);

        assertEquals(1, summary.updated());
        assertEquals(1, summary.notFound());
        assertEquals(List.of(ghost), summary.skipped());
        assertTrue(load(a).isHrOnly());
    }

    @Test
    void bulkRejectsAnEmptySelectionOrAnEmptyChange() {
        String a = persistDoc("a.pdf");
        assertThrows(BadRequestException.class, () -> service.updateFlags(List.of(), true, null, actorUuid));
        assertThrows(BadRequestException.class, () -> service.updateFlags(null, true, null, actorUuid));
        assertThrows(BadRequestException.class, () -> service.updateFlags(List.of(a), null, null, actorUuid));
    }

    // ── Display name via PATCH ─────────────────────────────────────────────

    @Test
    void patchingADisplayNameNormalizesItAndNeverTouchesTheOriginal() {
        String a = persistDoc("loenreg_2021_final(2).pdf");

        service.update(a, new PatchCommand(null, null, null, null, null,
                "2021_SALARY_lønregulering.docx"), actorUuid);

        EmployeeDocument doc = load(a);
        assertEquals("2021_SALARY_lønregulering.pdf", doc.getDisplayName(),
                "the original file's extension wins");
        assertEquals("loenreg_2021_final(2).pdf", doc.getOriginalFilename(),
                "original_filename is immutable");
    }

    @Test
    void anExplicitEmptyDisplayNameResetsToTheOriginalFilename() {
        String a = persistDoc("loenreg.pdf");
        service.update(a, new PatchCommand(null, null, null, null, null, "Noget andet.pdf"), actorUuid);
        assertEquals("Noget andet.pdf", load(a).getDisplayName());

        service.update(a, new PatchCommand(null, null, null, null, null, ""), actorUuid);

        assertNull(load(a).getDisplayName(), "empty string = reset");
        assertEquals("loenreg.pdf", EmployeeDocumentService.servingFilename(load(a)));
    }

    @Test
    void anAbsentDisplayNameLeavesTheExistingOneAlone() {
        String a = persistDoc("loenreg.pdf");
        service.update(a, new PatchCommand(null, null, null, null, null, "Navn.pdf"), actorUuid);

        service.update(a, new PatchCommand(EmployeeDocumentCategory.SALARY, null, null, null, null, null),
                actorUuid);

        assertEquals("Navn.pdf", load(a).getDisplayName());
        assertEquals(EmployeeDocumentCategory.SALARY, load(a).getCategory());
    }

    @Test
    void servingFilenameFallsBackToTheOriginalWhenThereIsNoDisplayName() {
        EmployeeDocument doc = load(persistDoc("original.pdf"));
        assertEquals("original.pdf", EmployeeDocumentService.servingFilename(doc));
        doc.setDisplayName("   ");
        assertEquals("original.pdf", EmployeeDocumentService.servingFilename(doc),
                "a blank display name is not a name");
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private String persistDoc(String filename) {
        String uuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument doc = new EmployeeDocument();
            doc.setUuid(uuid);
            doc.setUserUuid(userUuid);
            doc.setS3Key("users/" + userUuid + "/" + uuid + "-test");
            doc.setCategory(EmployeeDocumentCategory.OTHER);
            doc.setOriginalFilename(filename);
            doc.setContentType("application/pdf");
            doc.setFileSizeBytes(1234);
            doc.setSource(EmployeeDocumentSource.MANUAL_HR);
            doc.persist();
        });
        return uuid;
    }

    private EmployeeDocument load(String uuid) {
        return QuarkusTransaction.requiringNew().call(() -> EmployeeDocument.findById(uuid));
    }

    private long auditCount(EmployeeDocumentAuditAction action) {
        return QuarkusTransaction.requiringNew().call(() ->
                EmployeeDocumentAudit.count("userUuid = ?1 AND action = ?2", userUuid, action));
    }
}
