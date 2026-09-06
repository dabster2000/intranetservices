package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment;
import dk.trustworks.intranet.bi.services.InternalBudgetCalculatingExecutor.Spread;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The daily spread and stacking rule (spec §4.3.3, §4.3.6): {@code hours_per_week / 5} per
 * assignment, scaled down proportionally when the approved internal hours exceed what the
 * contract budgets left of the day's net availability.
 */
class InternalBudgetCalculatingExecutorTest {

    private static InternalAssignment approved(String uuid, String hoursPerWeek, boolean strategic) {
        InternalAssignment a = new InternalAssignment();
        a.setUuid(uuid);
        a.setHoursPerWeek(new BigDecimal(hoursPerWeek));
        a.setStrategic(strategic);
        a.setStatus(InternalAssignment.Status.APPROVED);
        return a;
    }

    @Test
    void oneAssignmentWithinCapacityIsSpreadOverFive() {
        List<Spread> out = InternalBudgetCalculatingExecutor.spread(List.of(approved("a", "5", true)), 4.0);
        assertEquals(1, out.size());
        assertEquals(1.0, out.get(0).unadjusted(), 1e-9);
        assertEquals(1.0, out.get(0).adjusted(), 1e-9);
        assertEquals(true, out.get(0).strategic());
    }

    @Test
    void stackingBeyondRemainingCapacityScalesEveryAssignmentProportionally() {
        // 10 h/w + 5 h/w = 2 h + 1 h per day, but only 1.5 h of the day is left after client work.
        List<Spread> out = InternalBudgetCalculatingExecutor.spread(
                List.of(approved("a", "10", false), approved("b", "5", true)), 1.5);
        assertEquals(2.0, out.get(0).unadjusted(), 1e-9);
        assertEquals(1.0, out.get(0).adjusted(), 1e-9);
        assertEquals(1.0, out.get(1).unadjusted(), 1e-9);
        assertEquals(0.5, out.get(1).adjusted(), 1e-9);
    }

    @Test
    void aDayWithNoRemainingCapacityYieldsZeroAdjustedHoursButKeepsTheClaim() {
        // A contracted junior with no declaration (D7): net 0 → internal 0, unadjusted still recorded.
        List<Spread> out = InternalBudgetCalculatingExecutor.spread(List.of(approved("a", "5", false)), 0.0);
        assertEquals(1.0, out.get(0).unadjusted(), 1e-9);
        assertEquals(0.0, out.get(0).adjusted(), 1e-9);
    }

    @Test
    void negativeRemainingCapacityIsTreatedAsZero() {
        List<Spread> out = InternalBudgetCalculatingExecutor.spread(List.of(approved("a", "5", false)), -3.0);
        assertEquals(0.0, out.get(0).adjusted(), 1e-9);
    }

    @Test
    void noApprovedAssignmentsIsNoRows() {
        assertEquals(0, InternalBudgetCalculatingExecutor.spread(List.of(), 7.4).size());
    }
}
