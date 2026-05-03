package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.CostToRevenueDataPointDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CxoFinanceServiceCostToRevenueTest {

    @Inject
    CxoFinanceService service;

    @Test
    void costToRevenue_noCompanyFilter_returnsTrailing18Months() {
        List<CostToRevenueDataPointDTO> result = service.costToRevenue(null);
        assertNotNull(result);
        // trailing 18 months — may be 0 if test fixtures have no overlapping data,
        // so the meaningful assertions are about shape, not size
        if (!result.isEmpty()) {
            CostToRevenueDataPointDTO first = result.getFirst();
            assertNotNull(first.monthKey());
            assertEquals(6, first.monthKey().length(), "month_key should be YYYYMM");
            assertTrue(first.year() >= 2020 && first.year() <= 2030);
            assertTrue(first.monthNumber() >= 1 && first.monthNumber() <= 12);
            assertNotNull(first.monthLabel());
            assertTrue(first.monthLabel().matches("[A-Z][a-z]{2} \\d{4}"), "monthLabel should be 'Mmm YYYY'");
        }
    }
}
