package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ScorecardRecommendation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubmitScorecardCommandTest {

    @Test
    void validate_failsIfAnyDimensionMissing() {
        SubmitScorecardCommand c = new SubmitScorecardCommand(
                null, (byte) 3, (byte) 3, (byte) 3, (byte) 3, (byte) 3,
                ScorecardRecommendation.HIRE, null, null, null);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void validate_failsIfDimensionOutOfRange() {
        SubmitScorecardCommand c = new SubmitScorecardCommand(
                (byte) 6, (byte) 3, (byte) 3, (byte) 3, (byte) 3, (byte) 3,
                ScorecardRecommendation.HIRE, null, null, null);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void validate_failsIfRecommendationMissing() {
        SubmitScorecardCommand c = new SubmitScorecardCommand(
                (byte) 3, (byte) 3, (byte) 3, (byte) 3, (byte) 3, (byte) 3,
                null, null, null, null);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void validate_passesWithCleanInput() {
        SubmitScorecardCommand c = new SubmitScorecardCommand(
                (byte) 3, (byte) 3, (byte) 3, (byte) 3, (byte) 3, (byte) 3,
                ScorecardRecommendation.HIRE, "good", null, null);
        assertDoesNotThrow(c::validate);
    }
}
