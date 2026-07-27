package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.MatchMethod;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMappingTransferService.ImportSummary;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMappingTransferService.MappingEntry;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMappingTransferService.MappingExport;
import dk.trustworks.intranet.domain.user.entity.User;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2a-9 import semantics against the real DB (runbook 2a-9 verify):
 * unknown folder / unknown user / already mapped / idempotent re-run /
 * match_method preserved — plus a full export→import round-trip.
 */
@QuarkusTest
class SharePointMappingTransferIntegrationTest {

    private static final String SITE = "https://test.sharepoint.local/sites/mapping-it";

    @Inject
    SharePointMappingTransferService transferService;

    private String userUuid;

    @BeforeEach
    void seed() {
        cleanup();
        userUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            User user = new User();
            user.setUuid(userUuid);
            user.setUsername("mapping.it.tester");
            user.setFirstname("Mapping");
            user.setLastname("Tester");
            user.setEmail("mapping.it@test.local");
            user.persist();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationFolder.delete("siteUrl", SITE);
            if (userUuid != null) User.delete("uuid", userUuid);
        });
    }

    @Test
    void importAppliesPreservesMethodAndIsIdempotent() {
        persistFolder("Alberte Test", null, MatchMethod.UNMATCHED, FolderStatus.DISCOVERED);
        MappingExport file = export(entry("Alberte Test", userUuid, "AI_CONFIRMED"));

        ImportSummary first = transferService.importMappings(file);
        assertEquals(new ImportSummary(1, 0, 0, 0), first);

        SharePointMigrationFolder folder = findFolder("Alberte Test");
        assertEquals(userUuid, folder.getMatchedUserUuid());
        assertEquals(MatchMethod.AI_CONFIRMED, folder.getMatchMethod(), "exported method is preserved");
        assertEquals(FolderStatus.MAPPED, folder.getStatus());

        // Idempotent: re-importing the same file applies 0.
        ImportSummary second = transferService.importMappings(file);
        assertEquals(new ImportSummary(0, 1, 0, 0), second);
        assertEquals(userUuid, findFolder("Alberte Test").getMatchedUserUuid());
    }

    @Test
    void unknownFolderAndUnknownUserFailClosedPerEntry() {
        persistFolder("Known Folder", null, MatchMethod.UNMATCHED, FolderStatus.DISCOVERED);

        ImportSummary summary = transferService.importMappings(export(
                entry("No Such Folder", userUuid, "MANUAL"),
                entry("Known Folder", "00000000-dead-beef-0000-000000000000", "MANUAL")));

        assertEquals(new ImportSummary(0, 0, 1, 1), summary);
        // The hallucinated uuid was never written.
        assertEquals(null, findFolder("Known Folder").getMatchedUserUuid());
    }

    @Test
    void existingMappingIsNeverOverwritten() {
        String otherUser = UUID.randomUUID().toString();
        persistFolder("Already Mapped", userUuid, MatchMethod.USERNAME, FolderStatus.MAPPED);

        ImportSummary summary = transferService.importMappings(export(
                entry("Already Mapped", otherUser, "MANUAL")));

        assertEquals(new ImportSummary(0, 1, 0, 0), summary);
        SharePointMigrationFolder folder = findFolder("Already Mapped");
        assertEquals(userUuid, folder.getMatchedUserUuid(), "existing mapping untouched");
        assertEquals(MatchMethod.USERNAME, folder.getMatchMethod());
    }

    @Test
    void exportImportRoundTrip() {
        persistFolder("Round Trip", userUuid, MatchMethod.MANUAL, FolderStatus.MAPPED);

        MappingExport exported = transferService.export();
        List<MappingEntry> mine = exported.mappings().stream()
                .filter(m -> m.siteUrl().equals(SITE)).toList();
        assertEquals(1, mine.size());
        assertEquals(userUuid, mine.get(0).matchedUserUuid());
        assertEquals("MANUAL", mine.get(0).matchMethod());
        assertEquals(1, exported.version());

        // Wipe the mapping (fresh-crawl shape) and re-import the export.
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationFolder folder = SharePointMigrationFolder
                    .findBySiteAndPath(SITE, "Round Trip");
            folder.setMatchedUserUuid(null);
            folder.setMatchMethod(MatchMethod.UNMATCHED);
            folder.setStatus(FolderStatus.DISCOVERED);
            folder.persist();
        });

        ImportSummary summary = transferService.importMappings(
                new MappingExport(1, exported.exportedAt(), mine));
        assertEquals(new ImportSummary(1, 0, 0, 0), summary);
        SharePointMigrationFolder restored = findFolder("Round Trip");
        assertEquals(userUuid, restored.getMatchedUserUuid());
        assertEquals(MatchMethod.MANUAL, restored.getMatchMethod());
        assertEquals(FolderStatus.MAPPED, restored.getStatus());
    }

    @Test
    void structuralValidationRejectsTheWholeFile() {
        assertThrows(BadRequestException.class, () ->
                transferService.importMappings(null));
        assertThrows(BadRequestException.class, () ->
                transferService.importMappings(new MappingExport(2, null,
                        List.of(entry("x", userUuid, "MANUAL")))));
        assertThrows(BadRequestException.class, () ->
                transferService.importMappings(export(entry("x", userUuid, "UNMATCHED"))));
        assertThrows(BadRequestException.class, () ->
                transferService.importMappings(export(entry("x", userUuid, "SOMETHING"))));
        assertThrows(BadRequestException.class, () ->
                transferService.importMappings(export(
                        new MappingEntry(SITE, "  ", userUuid, "MANUAL"))));
        assertTrue(SharePointMappingTransferService.isImportableMethod("AI_CONFIRMED"));
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private MappingEntry entry(String folder, String uuid, String method) {
        return new MappingEntry(SITE, folder, uuid, method);
    }

    private MappingExport export(MappingEntry... entries) {
        return new MappingExport(1, "2026-07-27T13:00:00", List.of(entries));
    }

    private void persistFolder(String name, String matchedUserUuid,
                               MatchMethod method, FolderStatus status) {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationFolder folder = new SharePointMigrationFolder();
            folder.setSiteUrl(SITE);
            folder.setFolderPath(name);
            folder.setFolderName(name);
            folder.setMatchedUserUuid(matchedUserUuid);
            folder.setMatchMethod(method);
            folder.setStatus(status);
            folder.persist();
        });
    }

    private SharePointMigrationFolder findFolder(String name) {
        return QuarkusTransaction.requiringNew().call(() ->
                SharePointMigrationFolder.findBySiteAndPath(SITE, name));
    }
}
