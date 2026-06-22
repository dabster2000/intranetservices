package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the recency-window cutoff direction (minus, not plus) and off-by-one,
 * without a database. The seeded-row selection itself is covered by the
 * CI-gated ExpenseSyncSelectionIT (@QuarkusTest).
 */
class ExpenseSyncCutoffTest {

    @Test
    void cutoff_is_today_minus_recency_days() {
        assertEquals(LocalDate.of(2026, 5, 22),
                ExpenseSyncBatchlet.computeCutoff(LocalDate.of(2026, 6, 21), 30));
    }

    @Test
    void cutoff_zero_days_is_today() {
        assertEquals(LocalDate.of(2026, 6, 21),
                ExpenseSyncBatchlet.computeCutoff(LocalDate.of(2026, 6, 21), 0));
    }
}
