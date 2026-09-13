package dk.trustworks.intranet.aggregates.crm.plan.services;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionCadence;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.BuyingRole;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.MeasureKind;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanRag;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanSlot;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The rules {@link AccountPlanService} enforces that the browser also enforces — enforced
 * twice on purpose, because a rule that only lives in the frontend is not a rule.
 *
 * <p>Fast tier: no Quarkus boot, no database. The transactional methods are exercised
 * against the local database during verification; what is locked here is the parsing,
 * clamping and scheduling they all go through.
 */
class AccountPlanServiceTest {

    // ------------------------------------------------------------------------
    // Enums
    // ------------------------------------------------------------------------

    @Test
    void enumsParseCaseInsensitivelyAndNameTheirFieldWhenTheyDoNot() {
        assertEquals(PlanSlot.DESIRED, AccountPlanService.parse(PlanSlot.class, "desired", "slot"));
        assertEquals(PlanRag.AMBER, AccountPlanService.parse(PlanRag.class, " Amber ", "rag"));
        assertEquals(MeasureKind.CONSULTANTS,
                AccountPlanService.parse(MeasureKind.class, "consultants", "measureKind"));
        assertEquals(BuyingRole.ECONOMIC_BUYER,
                AccountPlanService.parse(BuyingRole.class, "economic_buyer", "buying"));

        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> AccountPlanService.parse(PlanSlot.class, "MOTTO", "slot"));
        assertEquals(400, error.getResponse().getStatus());
        assertEquals("Unknown slot: MOTTO", error.getMessage());
    }

    @Test
    void anAbsentEnumValueIsRefusedRatherThanDefaulted() {
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> AccountPlanService.parse(PlanSlot.class, null, "slot"));
        assertEquals("slot is required", error.getMessage());
    }

    // ------------------------------------------------------------------------
    // Relationship ratings
    // ------------------------------------------------------------------------

    /**
     * The scale is 0-4 and nothing else. A 7 from a hand-written API call would render as a
     * bar wider than its track and, worse, would read as a stronger relationship than the
     * scale can express.
     */
    @Test
    void relationshipRatingsAreClampedToTheScale() {
        assertEquals(0, AccountPlanService.clamp(-3));
        assertEquals(0, AccountPlanService.clamp(0));
        assertEquals(3, AccountPlanService.clamp(3));
        assertEquals(4, AccountPlanService.clamp(4));
        assertEquals(4, AccountPlanService.clamp(9));
    }

    // ------------------------------------------------------------------------
    // Recurring actions
    // ------------------------------------------------------------------------

    @Test
    void aCadenceSchedulesItsNextOccurrence() {
        LocalDate from = LocalDate.of(2026, 9, 13);
        assertEquals(LocalDate.of(2026, 9, 20),
                AccountPlanService.nextOccurrence(ActionCadence.WEEKLY, from));
        assertEquals(LocalDate.of(2026, 10, 13),
                AccountPlanService.nextOccurrence(ActionCadence.MONTHLY, from));
        assertEquals(LocalDate.of(2026, 12, 13),
                AccountPlanService.nextOccurrence(ActionCadence.QUARTERLY, from));
    }

    /**
     * Month arithmetic at the end of a month. Adding a month to 31 January must give
     * 28 February, not an exception or 3 March.
     */
    @Test
    void monthlyCadenceHandlesShortMonths() {
        assertEquals(LocalDate.of(2026, 2, 28),
                AccountPlanService.nextOccurrence(ActionCadence.MONTHLY, LocalDate.of(2026, 1, 31)));
    }

    // ------------------------------------------------------------------------
    // Trimming
    // ------------------------------------------------------------------------

    /**
     * An empty sentence must become null, not "". {@code planGaps()} in the frontend asks
     * whether a slot is filled, and an empty string would answer yes — a plan would then
     * report itself complete with four blank sentences.
     */
    @Test
    void blankTextBecomesNullSoAnEmptySlotReadsAsEmpty() {
        assertNull(AccountPlanService.trimTo("", 100));
        assertNull(AccountPlanService.trimTo("   ", 100));
        assertNull(AccountPlanService.trimTo(null, 100));
        assertEquals("We are the integration partner",
                AccountPlanService.trimTo("  We are the integration partner  ", 100));
    }

    @Test
    void textLongerThanTheColumnIsCutToFitRatherThanRejected() {
        assertEquals("abcde", AccountPlanService.trimTo("abcdefghij", 5));
    }

    // ------------------------------------------------------------------------
    // The cap
    // ------------------------------------------------------------------------

    /** Four, from the spec. Pinned so a later "just one more" has to be a deliberate change. */
    @Test
    void aPlanHoldsAtMostFourObjectives() {
        assertEquals(4, AccountPlanService.MAX_OBJECTIVES);
    }
}
