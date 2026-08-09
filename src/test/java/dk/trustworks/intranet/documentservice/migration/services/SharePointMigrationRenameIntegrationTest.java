package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationCategorizerService.LinkSummary;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationRenameService.RenameSummary;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.services.AppSettingService;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * M6 rename pass (V476) against the real DB.
 *
 * <p>The first test is the reason this feature is safe to ship: a rename
 * pass must leave {@code original_filename} byte-identical, because the
 * signing linkage (decision A4) matches the legacy SharePoint filename
 * against that exact column. If a rename overwrote it, every signed
 * employment contract would silently fall to UNMATCHED.</p>
 */
@QuarkusTest
class SharePointMigrationRenameIntegrationTest {

    private static final String AI_KEY = "employee_documents.migration.ai.enabled";

    @Inject
    SharePointMigrationRenameService renameService;

    @Inject
    SharePointMigrationCategorizerService categorizerService;

    @Inject
    SigningCaseRepository signingCaseRepository;

    @Inject
    AppSettingService appSettingService;

    @InjectMock
    OpenAIService openAIService;

    private final String userUuid = UUID.randomUUID().toString();
    private final List<String> caseKeys = new ArrayList<>();

    @BeforeEach
    void arrange() {
        cleanup();
        // The whole feature must work with AI off (spec decision A5).
        appSettingService.saveSetting(AI_KEY, "false", "employee_documents", "rename-it");
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument.delete("userUuid", userUuid);
            for (String caseKey : caseKeys) {
                signingCaseRepository.delete("caseKey", caseKey);
            }
        });
        caseKeys.clear();
    }

    // ── The regression guard ───────────────────────────────────────────────

    @Test
    void renamePassLeavesOriginalFilenamesByteIdenticalAndLinkageUnchanged() {
        // A signed contract exactly as the migration stores it, plus the
        // UPLOADED signing case that must find it by filename.
        String signedFilename = "Ansættelseskontrakt_signed_2025-12-11.pdf";
        String signedUuid = persistDoc("", signedFilename, EmployeeDocumentCategory.CONTRACT);
        String caseKey = persistSigningCase("Ansættelseskontrakt.pdf",
                "https://trustworks.sharepoint.com/sites/hr/Docs/"
                        + "Ans%C3%A6ttelseskontrakt_signed_2025-12-11.pdf?web=1");

        // Plus a few ordinary migrated documents to rename alongside it.
        persistDoc("", "loenreg_2021_final(2).pdf", EmployeeDocumentCategory.SALARY);
        persistDoc("Arkiv", "Kontrakt Anders FINAL FINAL.docx", EmployeeDocumentCategory.CONTRACT);

        Map<String, String> filenamesBefore = originalFilenames();

        RenameSummary summary = renameService.rename(false);
        assertTrue(summary.candidates() >= 3);

        // 1. original_filename byte-identical, every row.
        assertEquals(filenamesBefore, originalFilenames(),
                "original_filename is immutable — the signing linkage matches on it");
        assertEquals(signedFilename, load(signedUuid).getOriginalFilename());

        // 2. …and the display name really was written.
        assertNotNull(load(signedUuid).getDisplayName());

        // 3. The linkage still finds the case, after the rename.
        LinkSummary linkage = categorizerService.linkSigningCases(new ArrayList<>());
        assertTrue(linkage.linked() >= 1,
                "the signed contract must still link after a rename: " + linkage);
        assertEquals(caseKey, load(signedUuid).getSigningCaseKey(),
                "linked to the right case, by original filename");
    }

    @Test
    void matchingIsUnaffectedByADisplayNameThatWouldNotMatch() {
        // Belt and braces on the same invariant, at the matcher level:
        // the matcher only ever sees original_filename.
        var pattern = SharePointMigrationCategorizerService.signedPattern("Ansættelseskontrakt.pdf");
        assertTrue(SharePointMigrationCategorizerService.matchesExactly(
                "Ansættelseskontrakt_signed_2025-12-11.pdf", null, pattern));
        assertFalse(SharePointMigrationCategorizerService.matchesExactly(
                "2025-12-11_CONTRACT_ansaettelseskontrakt.pdf", null, pattern),
                "a display name would NOT match — which is exactly why we never write it to original_filename");
    }

    // ── Run semantics ──────────────────────────────────────────────────────

    @Test
    void aiOffNamesEveryDocumentDeterministicallyWithZeroOpenAiCalls() {
        persistDoc("", "loenreg_2021_final(2).pdf", EmployeeDocumentCategory.SALARY);
        persistDoc("Arkiv", "Kontrakt Anders FINAL FINAL.docx", EmployeeDocumentCategory.CONTRACT);
        persistDoc("", "Scan_20190304.pdf", EmployeeDocumentCategory.IDENTITY);

        RenameSummary summary = renameService.rename(false);

        assertFalse(summary.aiUsed());
        assertEquals(0, summary.aiNamed());
        assertEquals(3, summary.tableNamed());
        assertTrue(summary.errors().isEmpty());

        assertEquals("2021_SALARY_loenreg.pdf", displayNameOf("loenreg_2021_final(2).pdf"));
        assertEquals("CONTRACT_kontrakt-anders.docx", displayNameOf("Kontrakt Anders FINAL FINAL.docx"));
        assertEquals("2019-03-04_IDENTITY_scan.pdf", displayNameOf("Scan_20190304.pdf"));

        verify(openAIService, never()).askQuestionWithSchema(
                anyString(), anyString(), any(), anyString(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void dryRunWritesNothing() {
        String docUuid = persistDoc("", "loenreg_2021_final(2).pdf", EmployeeDocumentCategory.SALARY);

        RenameSummary summary = renameService.rename(true);

        assertTrue(summary.dryRun());
        assertEquals(1, summary.candidates());
        assertEquals(1, summary.proposals().size());
        assertEquals("2021_SALARY_loenreg.pdf", summary.proposals().get(0).proposedName());
        assertEquals("loenreg_2021_final(2).pdf", summary.proposals().get(0).originalFilename());

        assertNull(load(docUuid).getDisplayName(), "dryRun=true must not write a single row");
    }

    @Test
    void rerunningIsANoOpAndHrEditsSurvive() {
        String docUuid = persistDoc("", "loenreg_2021_final(2).pdf", EmployeeDocumentCategory.SALARY);
        String editedUuid = persistDoc("", "notat.pdf", EmployeeDocumentCategory.OTHER);
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument doc = EmployeeDocument.findById(editedUuid);
            doc.setDisplayName("HR kalder det her noget andet.pdf");
            doc.persist();
        });

        assertEquals(1, renameService.rename(false).candidates(),
                "the HR-named document is not a candidate — display_name is already set");
        String firstPass = load(docUuid).getDisplayName();

        RenameSummary second = renameService.rename(false);

        assertEquals(0, second.candidates(), "a second pass has nothing left to do");
        assertEquals(firstPass, load(docUuid).getDisplayName());
        assertEquals("HR kalder det her noget andet.pdf", load(editedUuid).getDisplayName(),
                "HR's manual edit is sticky");
    }

    @Test
    void nonMigrationDocumentsAreOutOfScope() {
        String hrUpload = persistDoc("", "kontrakt.pdf", EmployeeDocumentCategory.CONTRACT);
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument doc = EmployeeDocument.findById(hrUpload);
            doc.setSource(EmployeeDocumentSource.MANUAL_HR);
            doc.persist();
        });

        assertEquals(0, renameService.rename(false).candidates());
        assertNull(load(hrUpload).getDisplayName());
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private String persistDoc(String label, String filename, EmployeeDocumentCategory category) {
        String uuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument doc = new EmployeeDocument();
            doc.setUuid(uuid);
            doc.setUserUuid(userUuid);
            doc.setS3Key("users/" + userUuid + "/" + uuid + "-test");
            doc.setCategory(category);
            doc.setLabel(label.isBlank() ? null : label);
            doc.setOriginalFilename(filename);
            doc.setContentType("application/pdf");
            doc.setFileSizeBytes(1234);
            doc.setSource(EmployeeDocumentSource.MIGRATION);
            doc.setMigratedFrom("test://rename-it/" + uuid);
            doc.persist();
        });
        return uuid;
    }

    private String persistSigningCase(String documentName, String sharepointFileUrl) {
        String caseKey = "renameit" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        caseKeys.add(caseKey);
        QuarkusTransaction.requiringNew().run(() -> {
            SigningCase signingCase = new SigningCase();
            signingCase.setCaseKey(caseKey);
            signingCase.setUserUuid(userUuid);
            signingCase.setDocumentName(documentName);
            signingCase.setStatus("COMPLETED");
            signingCase.setProcessingStatus("COMPLETED");
            signingCase.setSharepointUploadStatus("UPLOADED");
            signingCase.setSharepointFileUrl(sharepointFileUrl);
            signingCase.setArchiveStatus("PENDING");
            signingCaseRepository.persist(signingCase);
        });
        return caseKey;
    }

    /** original_filename per document uuid — the before/after fingerprint. */
    private Map<String, String> originalFilenames() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<String, String> byUuid = new LinkedHashMap<>();
            EmployeeDocument.<EmployeeDocument>list("userUuid", userUuid)
                    .forEach(d -> byUuid.put(d.getUuid(), d.getOriginalFilename()));
            return byUuid;
        });
    }

    private String displayNameOf(String originalFilename) {
        return QuarkusTransaction.requiringNew().call(() ->
                EmployeeDocument.<EmployeeDocument>find(
                                "userUuid = ?1 AND originalFilename = ?2", userUuid, originalFilename)
                        .firstResult().getDisplayName());
    }

    private EmployeeDocument load(String uuid) {
        return QuarkusTransaction.requiringNew().call(() -> EmployeeDocument.findById(uuid));
    }
}
