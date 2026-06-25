package dk.trustworks.intranet.financeservice.jobs;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit test (no DB / no Quarkus): pins the recency-window direction so the
 * BOOKED-invoice poll cutoff can never silently invert (which would either scan
 * everything or scan nothing).
 */
class EconomicsInvoiceStatusSyncCutoffTest {

    @Test
    void cutoff_is_today_minus_recency_days() {
        assertEquals(LocalDate.of(2025, 6, 25),
                EconomicsInvoiceStatusSyncBatchlet.computeCutoff(LocalDate.of(2026, 6, 25), 365),
                "365-day window goes back exactly one year");
        assertEquals(LocalDate.of(2026, 5, 26),
                EconomicsInvoiceStatusSyncBatchlet.computeCutoff(LocalDate.of(2026, 6, 25), 30),
                "30-day window goes back 30 days");
    }
}
