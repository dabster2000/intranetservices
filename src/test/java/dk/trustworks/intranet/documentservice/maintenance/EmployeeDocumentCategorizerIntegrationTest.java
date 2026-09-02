package dk.trustworks.intranet.documentservice.maintenance;

import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.documentservice.maintenance.EmployeeDocumentCategorizerService.AiVerdict;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.services.AppSettingService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Categorizer apply-semantics against the real DB (runbook 2a-5 verify):
 * toggle OFF ⇒ zero OpenAI calls and pure rule-table results with no
 * review flags; HIGH applies; MEDIUM falls back + flags; HR edits are
 * never overwritten.
 */
@QuarkusTest
class EmployeeDocumentCategorizerIntegrationTest {

    private static final String AI_KEY = "employee_documents.migration.ai.enabled";

    @Inject
    EmployeeDocumentCategorizerService categorizer;

    @Inject
    AppSettingService appSettingService;

    @InjectMock
    OpenAIService openAIService;

    private final String userUuid = UUID.randomUUID().toString();

    @BeforeEach
    void arrange() {
        cleanup();
        appSettingService.saveSetting(AI_KEY, "false", "employee_documents", "categorizer-it");
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() ->
                EmployeeDocument.delete("userUuid", userUuid));
    }

    @Test
    void toggleOffUsesRuleTableOnlyAndNeverCallsOpenAi() {
        String sickness = persistDoc("Sygdom", "mulighedserklæring.pdf");
        String archived = persistDoc("Arkiv", "Lønregulering 2019.pdf");
        String plain = persistDoc("", "scan0001.pdf");

        categorizer.categorize();

        EmployeeDocument sicknessDoc = load(sickness);
        assertEquals(EmployeeDocumentCategory.SICKNESS, sicknessDoc.getCategory());
        assertFalse(sicknessDoc.isNeedsReview(), "AI off ⇒ pre-AI behavior, no review flag");

        EmployeeDocument archivedDoc = load(archived);
        assertEquals(EmployeeDocumentCategory.SALARY, archivedDoc.getCategory());
        assertTrue(archivedDoc.isArchived(), "Arkiv path sets archived");

        assertEquals(EmployeeDocumentCategory.OTHER, load(plain).getCategory());

        // V476: with AI off every document still gets a deterministic
        // display name off the same rule table — and none of them lost
        // their original filename.
        assertEquals("SICKNESS_mulighedserklæring.pdf", sicknessDoc.getDisplayName());
        assertEquals("mulighedserklæring.pdf", sicknessDoc.getOriginalFilename());
        assertEquals("2019_SALARY_lønregulering.pdf", archivedDoc.getDisplayName());
        assertEquals("Lønregulering 2019.pdf", archivedDoc.getOriginalFilename());
        assertEquals("OTHER_scan.pdf", load(plain).getDisplayName());

        verify(openAIService, never()).askQuestionWithSchema(
                anyString(), anyString(), any(), anyString(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void highVerdictAppliesDirectly() {
        String docUuid = persistDoc("", "loenreg_2021_final(2).pdf");
        AiVerdict high = new AiVerdict(EmployeeDocumentCategory.SALARY, true, "Lønregulering 2021", "HIGH",
                "2021_SALARY_loenregulering.pdf", false);

        boolean applied = categorizer.applyVerdict(docUuid, "", "loenreg_2021_final(2).pdf", high, true, false);

        assertTrue(applied);
        EmployeeDocument doc = load(docUuid);
        assertEquals(EmployeeDocumentCategory.SALARY, doc.getCategory());
        assertTrue(doc.isArchived());
        assertEquals("Lønregulering 2021", doc.getLabel());
        assertFalse(doc.isNeedsReview(), "AI-HIGH is trusted — no review flag");
        assertEquals("2021_SALARY_loenregulering.pdf", doc.getDisplayName());
        assertEquals("loenreg_2021_final(2).pdf", doc.getOriginalFilename(),
                "the original filename is immutable — the signing linkage matches on it");
    }

    @Test
    void mediumVerdictFallsBackToRuleTableAndFlags() {
        String docUuid = persistDoc("Sygdom", "notat.pdf");
        AiVerdict medium = new AiVerdict(EmployeeDocumentCategory.CONTRACT, false, null, "MEDIUM", null, false);

        boolean applied = categorizer.applyVerdict(docUuid, "Sygdom", "notat.pdf", medium, true, false);

        assertFalse(applied);
        EmployeeDocument doc = load(docUuid);
        assertEquals(EmployeeDocumentCategory.SICKNESS, doc.getCategory(),
                "MEDIUM must not apply — the rule table decides");
        assertTrue(doc.isNeedsReview(), "fallback after an AI attempt flags for HR review");
    }

    @Test
    void hrEditsAreNeverOverwritten() {
        String docUuid = persistDoc("", "something.pdf");
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument doc = EmployeeDocument.findById(docUuid);
            doc.setCategory(EmployeeDocumentCategory.CONTRACT);
            doc.persist();
        });

        AiVerdict high = new AiVerdict(EmployeeDocumentCategory.SALARY, false, null, "HIGH", null, false);
        categorizer.applyVerdict(docUuid, "", "something.pdf", high, true, false);

        assertEquals(EmployeeDocumentCategory.CONTRACT, load(docUuid).getCategory(),
                "a non-default category means HR already decided — hands off");
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private String persistDoc(String label, String filename) {
        String uuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument doc = new EmployeeDocument();
            doc.setUuid(uuid);
            doc.setUserUuid(userUuid);
            doc.setS3Key("users/" + userUuid + "/" + uuid + "-test");
            doc.setCategory(EmployeeDocumentCategory.OTHER);
            doc.setLabel(label.isBlank() ? null : label);
            doc.setOriginalFilename(filename);
            doc.setContentType("application/pdf");
            doc.setFileSizeBytes(1234);
            doc.setSource(EmployeeDocumentSource.MIGRATION);
            doc.setMigratedFrom("test://categorizer-it/" + uuid);
            doc.persist();
        });
        return uuid;
    }

    private EmployeeDocument load(String uuid) {
        return QuarkusTransaction.requiringNew().call(() -> EmployeeDocument.findById(uuid));
    }
}
