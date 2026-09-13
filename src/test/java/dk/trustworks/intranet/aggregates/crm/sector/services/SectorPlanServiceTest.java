package dk.trustworks.intranet.aggregates.crm.sector.services;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionCadence;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanSlot;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The derived colour of a sector objective, and the rules the sector plan shares with the
 * account plan (sectors spec §3.3). Fast tier: no Quarkus boot, no database.
 */
class SectorPlanServiceTest {

    // ------------------------------------------------------------------------
    // The derived colour — worst of the account objectives serving a sector objective
    // ------------------------------------------------------------------------

    @Test
    void redBeatsAmberBeatsGreen() {
        assertEquals("RED", SectorPlanService.worstOf(List.of("GREEN", "RED", "AMBER")));
        assertEquals("AMBER", SectorPlanService.worstOf(List.of("GREEN", "AMBER", "GREEN")));
        assertEquals("GREEN", SectorPlanService.worstOf(List.of("GREEN", "GREEN")));
    }

    /** Rule 8 at sector level: nothing assessed underneath means nothing derived — never green. */
    @Test
    void nothingAssessedDerivesNothing() {
        assertNull(SectorPlanService.worstOf(List.of()));
        assertNull(SectorPlanService.worstOf(Arrays.asList((String) null, null)));
    }

    @Test
    void unassessedServingObjectivesAreIgnoredNotCountedAsGreen() {
        assertEquals("AMBER", SectorPlanService.worstOf(Arrays.asList(null, "AMBER", null)));
        assertEquals("GREEN", SectorPlanService.worstOf(Arrays.asList(null, "GREEN")));
    }

    /** A serving objective's green without a reason is not assessed, exactly as on its own plan. */
    @Test
    void aGreenWithoutAReasonIsNotAssessed() {
        assertNull(SectorPlanService.effectiveRag("GREEN", null));
        assertNull(SectorPlanService.effectiveRag("GREEN", "   "));
        assertEquals("GREEN", SectorPlanService.effectiveRag("GREEN", "Two extensions signed"));
        assertEquals("RED", SectorPlanService.effectiveRag("red", null));
        assertNull(SectorPlanService.effectiveRag(null, "a reason without a colour"));
    }

    // ------------------------------------------------------------------------
    // Shared rules
    // ------------------------------------------------------------------------

    @Test
    void aSectorPlanHoldsAtMostFourObjectives() {
        assertEquals(4, SectorPlanService.MAX_OBJECTIVES);
    }

    @Test
    void enumsParseCaseInsensitivelyAndNameTheirField() {
        assertEquals(PlanSlot.DESIRED, SectorPlanService.parse(PlanSlot.class, "desired", "slot"));
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> SectorPlanService.parse(PlanSlot.class, "MOTTO", "slot"));
        assertEquals(400, error.getResponse().getStatus());
        assertEquals("Unknown slot: MOTTO", error.getMessage());
    }

    @Test
    void aCadenceSchedulesItsNextOccurrence() {
        LocalDate from = LocalDate.of(2026, 9, 13);
        assertEquals(LocalDate.of(2026, 9, 20), SectorPlanService.nextOccurrence(ActionCadence.WEEKLY, from));
        assertEquals(LocalDate.of(2026, 10, 13), SectorPlanService.nextOccurrence(ActionCadence.MONTHLY, from));
        assertEquals(LocalDate.of(2026, 12, 13), SectorPlanService.nextOccurrence(ActionCadence.QUARTERLY, from));
    }

    @Test
    void blankTextBecomesNullSoAnEmptySlotReadsAsEmpty() {
        assertNull(SectorPlanService.trimTo("", 100));
        assertNull(SectorPlanService.trimTo("   ", 100));
        assertEquals("abcde", SectorPlanService.trimTo("abcdefghij", 5));
    }
}
