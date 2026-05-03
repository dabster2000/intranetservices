package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.GrossMarginTrendDataPointDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CxoFinanceServiceGrossMarginTrendTest {

    @Inject
    CxoFinanceService service;

    @Test
    void grossMarginTrend_noCompanyFilter_executesAndReturnsList() {
        List<GrossMarginTrendDataPointDTO> result = service.grossMarginTrend(null);
        assertNotNull(result, "Service must return a list, never null");
        for (GrossMarginTrendDataPointDTO row : result) {
            assertNotNull(row.monthKey());
            assertEquals(6, row.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(row.year() >= 2020 && row.year() <= 2030);
            assertTrue(row.monthNumber() >= 1 && row.monthNumber() <= 12);
            assertNotNull(row.monthLabel());
            assertTrue(row.monthLabel().matches("[A-Z][a-z]{2} \\d{4}"));
            // HAVING clause guarantees revenue > 0 for every row
            assertTrue(row.totalRevenueDkk() > 0, "HAVING clause should exclude zero-revenue months");
        }
    }

    @Test
    void grossMarginTrend_withCompanyFilter_doesNotThrow() {
        Set<String> companyIds = Set.of("00000000-0000-0000-0000-000000000001",
                                        "00000000-0000-0000-0000-000000000002");
        List<GrossMarginTrendDataPointDTO> result = service.grossMarginTrend(companyIds);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "filter with random UUIDs should return empty");
    }
}
