package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusDeleteResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the delete-guard decision (P1#7 rule-side) and the dry-run preview horizon — no
 * Quarkus boot required (both are extracted, side-effect-free helpers).
 */
class IndividualBonusServiceTest {

    // --- decideDelete: soft-delete once a rule has paid, hard-delete when it never did ---

    @Test
    void decideDelete_withMaterializedPayout_softDeletes() {
        IndividualBonusDeleteResult result = IndividualBonusService.decideDelete("rule-1", true);
        assertEquals("rule-1", result.uuid());
        assertTrue(result.softDeleted(), "a rule that drove pay must be soft-deleted (kept, deactivated)");
        assertTrue(result.message().toLowerCase().contains("deactivated"));
    }

    @Test
    void decideDelete_neverPaid_hardDeletes() {
        IndividualBonusDeleteResult result = IndividualBonusService.decideDelete("rule-2", false);
        assertEquals("rule-2", result.uuid());
        assertFalse(result.softDeleted(), "a rule that never paid must be hard-deleted");
        assertTrue(result.message().toLowerCase().contains("hard-deleted"));
    }

    // --- previewHorizon: cover the trailing FY-close settlement (bounded) / ~2 FYs (open-ended) ---

    @Test
    void previewHorizon_boundedRule_extendsPastEffectiveToForTheSettlement() {
        // effectiveTo at FY-end → horizon must reach past the July-after-FY-end settlement pay-month.
        LocalDate horizon = IndividualBonusService.previewHorizon(
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 6, 30), LocalDate.of(2026, 7, 9));
        assertEquals(LocalDate.of(2028, 7, 1), horizon);
        assertTrue(horizon.isAfter(LocalDate.of(2027, 7, 1)), "must include the FY-close July settlement");
    }

    @Test
    void previewHorizon_openEndedFutureStart_anchorsOnEffectiveFrom() {
        // effectiveFrom in the future → anchor on it (not today) and show ~2 fiscal years.
        LocalDate horizon = IndividualBonusService.previewHorizon(
                LocalDate.of(2026, 9, 1), null, LocalDate.of(2026, 7, 9));
        assertEquals(LocalDate.of(2028, 10, 1), horizon);
    }

    @Test
    void previewHorizon_openEndedPastStart_anchorsOnToday() {
        // effectiveFrom already elapsed → anchor on today so the horizon is not stuck in the past.
        LocalDate horizon = IndividualBonusService.previewHorizon(
                LocalDate.of(2025, 1, 1), null, LocalDate.of(2026, 7, 9));
        assertEquals(LocalDate.of(2028, 8, 1), horizon);
    }
}
