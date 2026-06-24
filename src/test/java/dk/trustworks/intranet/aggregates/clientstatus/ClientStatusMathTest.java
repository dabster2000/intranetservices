package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCellState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

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
}
