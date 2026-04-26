package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InterviewTemplateCatalogTest {

    private final InterviewTemplateCatalog catalog = new InterviewTemplateCatalog();

    @Test
    void everyHiringCategoryRoundTypeCombination_hasNonNullTemplate() {
        for (HiringCategory hc : HiringCategory.values()) {
            for (InterviewRoundType rt : new InterviewRoundType[]{
                InterviewRoundType.FIRST, InterviewRoundType.CASE_OR_TECH, InterviewRoundType.FINAL}) {
                InterviewTemplateCatalog.InterviewTemplate t = catalog.templateFor(hc, rt);
                assertNotNull(t, "no template for " + hc + "/" + rt);
                assertTrue(t.defaultDurationMinutes() > 0, "duration must be positive");
                assertNotNull(t.suggestedFocusAreas());
                assertNotNull(t.defaultQuestions());
                assertNotNull(t.scorecardWeightHints());
                assertNotNull(t.defaultRequiredScorerRoles());
                assertFalse(t.defaultRequiredScorerRoles().isEmpty(),
                    "must have at least one default required scorer role");
            }
        }
    }

    @Test
    void specialRoundType_returnsNull_byDesign() {
        // SPECIAL rounds are ad-hoc and don't follow the catalog.
        for (HiringCategory hc : HiringCategory.values()) {
            assertNull(catalog.templateFor(hc, InterviewRoundType.SPECIAL),
                "SPECIAL must return null for " + hc);
        }
    }
}
