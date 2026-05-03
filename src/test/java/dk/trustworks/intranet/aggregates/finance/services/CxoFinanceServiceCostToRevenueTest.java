package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.CostToRevenueDataPointDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CxoFinanceServiceCostToRevenueTest {

    @Inject
    CxoFinanceService service;

    @Test
    void costToRevenue_noCompanyFilter_executesAndReturnsList() {
        // Verifies: SQL parses, executes against real schema, returns List (not null).
        // Shape assertions only run when fixtures provide data.
        List<CostToRevenueDataPointDTO> result = service.costToRevenue(null);
        assertNotNull(result, "Service must return a list, never null");
        for (CostToRevenueDataPointDTO row : result) {
            assertNotNull(row.monthKey(), "monthKey must be set");
            assertEquals(6, row.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(row.year() >= 2020 && row.year() <= 2030, "year out of expected range");
            assertTrue(row.monthNumber() >= 1 && row.monthNumber() <= 12, "monthNumber out of range");
            assertNotNull(row.monthLabel(), "monthLabel must be set");
            assertTrue(row.monthLabel().matches("[A-Z][a-z]{2} \\d{4}"), "monthLabel format must be 'Mmm YYYY'");
        }
    }

    @Test
    void costToRevenue_withCompanyFilter_doesNotThrow() {
        // Verifies: companyIds filter path SQL parses + runs.
        // Real UUIDs not needed — empty result is the expected outcome with random UUIDs.
        Set<String> companyIds = Set.of("00000000-0000-0000-0000-000000000001",
                                        "00000000-0000-0000-0000-000000000002");
        List<CostToRevenueDataPointDTO> result = service.costToRevenue(companyIds);
        assertNotNull(result, "Service must return a list even with no matching rows");
        assertTrue(result.isEmpty(), "filter with random UUIDs should return empty");
    }
}
