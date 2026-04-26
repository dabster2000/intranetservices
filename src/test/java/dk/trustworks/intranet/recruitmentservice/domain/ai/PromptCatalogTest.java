package dk.trustworks.intranet.recruitmentservice.domain.ai;

import dk.trustworks.intranet.recruitmentservice.domain.ai.prompts.PromptCatalog;
import dk.trustworks.intranet.recruitmentservice.domain.ai.schemas.SchemaCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PromptCatalogTest {

    @Inject PromptCatalog prompts;
    @Inject SchemaCatalog schemas;

    @Test
    void cvExtractionV1_loadsPromptAndSchema() {
        String p = prompts.load("cv-extraction-v1");
        assertNotNull(p);
        assertTrue(p.contains("CV"), "prompt mentions CV");

        String s = schemas.load("cv-extraction-v1");
        assertNotNull(s);
        assertTrue(s.contains("\"firstName\""), "schema declares firstName field");
        assertTrue(s.contains("\"workHistory\""), "schema declares workHistory");
    }

    @Test
    void roleBriefV1_loads() {
        assertNotNull(prompts.load("role-brief-v1"));
        String s = schemas.load("role-brief-v1");
        assertTrue(s.contains("\"responsibilities\""));
        assertTrue(s.contains("\"mustHaves\""));
    }

    @Test
    void candidateSummaryV1_loads() {
        assertNotNull(prompts.load("candidate-summary-v1"));
        String s = schemas.load("candidate-summary-v1");
        assertTrue(s.contains("\"summaryParagraph\""));
        assertTrue(s.contains("\"practiceMatchScore\""));
    }

    @Test
    void unknownVersionThrows() {
        assertThrows(IllegalArgumentException.class, () -> prompts.load("does-not-exist-v9"));
    }
}
