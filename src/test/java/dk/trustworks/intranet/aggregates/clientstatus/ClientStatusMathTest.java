package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCellState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCellState.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientStatusMathTest {

    @Test
    void classify_noActivity_whenBothZero() {
        assertEquals(NO_ACTIVITY, ClientStatusMath.classify(0d, 0d));
    }

    @Test
    void classify_over_whenInvoicedButNoWork() {
        assertEquals(OVER, ClientStatusMath.classify(0d, 5000d));
    }

    @Test
    void classify_notInvoiced_whenExpectedButZeroInvoiced() {
        assertEquals(NOT_INVOICED, ClientStatusMath.classify(10000d, 0d));
    }

    @Test
    void classify_partial_belowFullThreshold() {
        assertEquals(PARTIAL, ClientStatusMath.classify(10000d, 5000d)); // ratio 0.5
        assertEquals(PARTIAL, ClientStatusMath.classify(10000d, 9700d)); // ratio 0.97
    }

    @Test
    void classify_full_withinBand() {
        assertEquals(FULL, ClientStatusMath.classify(10000d, 9800d));  // 0.98
        assertEquals(FULL, ClientStatusMath.classify(10000d, 10000d)); // 1.00
        assertEquals(FULL, ClientStatusMath.classify(10000d, 10200d)); // 1.02
    }

    @Test
    void classify_over_aboveBand() {
        assertEquals(OVER, ClientStatusMath.classify(10000d, 10201d)); // > 1.02
    }

    @Test
    void ttmMonthKeys_returns12OldestToNewest() {
        List<String> keys = ClientStatusMath.ttmMonthKeys(YearMonth.of(2026, 6));
        assertEquals(12, keys.size());
        assertEquals("202507", keys.get(0));
        assertEquals("202606", keys.get(11));
    }

    @Test
    void ttmMonthKeys_handlesYearBoundary() {
        List<String> keys = ClientStatusMath.ttmMonthKeys(YearMonth.of(2026, 1));
        assertEquals("202502", keys.get(0));
        assertEquals("202601", keys.get(11));
    }

    @Test
    void ttmDateRange_isInclusiveStartExclusiveEnd() {
        YearMonth end = YearMonth.of(2026, 6);
        assertEquals(LocalDate.of(2025, 7, 1), ClientStatusMath.ttmFromDate(end));
        assertEquals(LocalDate.of(2026, 7, 1), ClientStatusMath.ttmToDateExclusive(end));
    }

    @Test
    void ttmPeriodRange_isInclusiveYearMonthKeys() {
        YearMonth end = YearMonth.of(2026, 6);
        assertEquals(202507, ClientStatusMath.ttmFromPeriod(end));
        assertEquals(202606, ClientStatusMath.ttmToPeriod(end));
    }

    @Test
    void provisionalMonthKeys_excludesCurrentMonthBeforeCutoff() {
        List<String> months = ClientStatusMath.ttmMonthKeys(YearMonth.of(2026, 6));
        // On Jun 24, June (202606) is provisional (Jul 10 not yet reached); May (202505) is final.
        Set<String> prov = ClientStatusMath.provisionalMonthKeys(months, LocalDate.of(2026, 6, 24));
        assertEquals(Set.of("202606"), prov);
    }

    @Test
    void provisionalMonthKeys_priorMonthStillProvisionalEarlyInNextMonth() {
        List<String> months = ClientStatusMath.ttmMonthKeys(YearMonth.of(2026, 6));
        // On Jul 5, June is still provisional because its Jul 10 cutoff has not passed.
        Set<String> prov = ClientStatusMath.provisionalMonthKeys(months, LocalDate.of(2026, 7, 5));
        assertTrue(prov.contains("202606"));
    }

    @Test
    void provisionalMonthKeys_finalizedExactlyOnCutoffDay() {
        List<String> months = ClientStatusMath.ttmMonthKeys(YearMonth.of(2026, 6));
        // On Jul 10, June is finalized (cutoff reached) and counts toward totals.
        Set<String> prov = ClientStatusMath.provisionalMonthKeys(months, LocalDate.of(2026, 7, 10));
        assertFalse(prov.contains("202606"));
    }

    @Test
    void provisionalMonthKeys_historicalWindowHasNone() {
        List<String> months = ClientStatusMath.ttmMonthKeys(YearMonth.of(2026, 3));
        Set<String> prov = ClientStatusMath.provisionalMonthKeys(months, LocalDate.of(2026, 6, 24));
        assertTrue(prov.isEmpty());
    }
}
