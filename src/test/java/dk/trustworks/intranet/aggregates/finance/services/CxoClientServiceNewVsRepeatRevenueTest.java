package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.NewVsRepeatClientRevenueDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.QuarterlyNewVsRepeatDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CxoClientServiceNewVsRepeatRevenueTest {

    @Inject
    CxoClientService service;

    @Test
    void newVsRepeatRevenue_defaultRange_returnsWrapper() {
        NewVsRepeatClientRevenueDTO result = service.newVsRepeatRevenue(null, null, null);
        assertNotNull(result);
        assertNotNull(result.quarters());
        for (QuarterlyNewVsRepeatDTO q : result.quarters()) {
            assertTrue(q.year() >= 2020 && q.year() <= 2030, "year out of range");
            assertTrue(q.quarter() >= 1 && q.quarter() <= 4);
            assertNotNull(q.quarterLabel());
            assertTrue(q.quarterLabel().matches("Q[1-4] \\d{4}"));
            assertTrue(q.newRevenueDkk() >= 0);
            assertTrue(q.repeatRevenueDkk() >= 0);
            assertEquals(q.newRevenueDkk() + q.repeatRevenueDkk(), q.totalRevenueDkk(), 0.01);
        }
    }

    @Test
    void newVsRepeatRevenue_explicitRange_doesNotThrow() {
        NewVsRepeatClientRevenueDTO result = service.newVsRepeatRevenue(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null);
        assertNotNull(result);
    }

    @Test
    void newVsRepeatRevenue_withCompanyFilter_doesNotThrow() {
        Set<String> companyIds = Set.of("00000000-0000-0000-0000-000000000001");
        NewVsRepeatClientRevenueDTO result = service.newVsRepeatRevenue(null, null, companyIds);
        assertNotNull(result);
    }
}
