package dk.trustworks.intranet.recruitmentservice.domain.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EnumValuesTest {

    @Test
    void practiceHasExactlySevenValues() {
        assertEquals(7, Practice.values().length);
        assertNotNull(Practice.valueOf("DEV"));
        assertNotNull(Practice.valueOf("SA"));
        assertNotNull(Practice.valueOf("BA"));
        assertNotNull(Practice.valueOf("PM"));
        assertNotNull(Practice.valueOf("CYB"));
        assertNotNull(Practice.valueOf("JK"));
        assertNotNull(Practice.valueOf("UD"));
    }

    @Test
    void hiringCategoryDerivesPipelineKindCorrectly() {
        assertEquals(PipelineKind.CONSULTANT, HiringCategory.PRACTICE_CONSULTANT.pipelineKind());
        assertEquals(PipelineKind.CONSULTANT, HiringCategory.JUNIOR_CONSULTANT.pipelineKind());
        assertEquals(PipelineKind.OTHER, HiringCategory.STAFF.pipelineKind());
        assertEquals(PipelineKind.OTHER, HiringCategory.PARTNER_OR_LEADERSHIP.pipelineKind());
        assertEquals(PipelineKind.OTHER, HiringCategory.SPECIAL_CASE.pipelineKind());
    }

    @Test
    void applicationStageEnumeratesAllSpecValues() {
        // Spec lists 11 named stages (SOURCED..CONVERTED + REJECTED, WITHDRAWN) plus TALENT_POOL = 12 total.
        assertEquals(12, ApplicationStage.values().length);
    }

    @Test
    void candidateStateEnumeratesAllSpecValues() {
        assertEquals(5, CandidateState.values().length); // NEW, ACTIVE, TALENT_POOL, ANONYMIZED, HIRED
    }
}
