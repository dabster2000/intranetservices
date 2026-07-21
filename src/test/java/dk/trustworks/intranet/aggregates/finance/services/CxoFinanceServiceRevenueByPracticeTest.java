package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.RevenuePracticeDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.MonthlyRevenuePracticeDataPoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CxoFinanceServiceRevenueByPracticeTest {

    @Inject
    CxoFinanceService service;

    @Inject
    dk.trustworks.intranet.services.PracticeService practiceService;

    @Test
    void revenueByPractice_defaultRange_returnsWrapper() {
        RevenuePracticeDTO result = service.revenueByPractice(null, null, null);
        assertNotNull(result);
        assertNotNull(result.months());
        assertNotNull(result.practices());
        // practices list is ordered; verify each entry is a live registry code or
        // the "OTHER" catch-all. Derived from the registry rather than hardcoded:
        // the Phase 5 fold renames the storage codes (SA->IA, BA->BU, DEV->TECH),
        // so a literal list would red the gate the moment V429 applies.
        List<String> known = new java.util.ArrayList<>(practiceService.activePracticeCodes());
        known.add("OTHER");
        for (String p : result.practices()) {
            assertTrue(known.contains(p),
                    "Unexpected practice id: " + p + " (live registry: " + known + ")");
        }
        // Each month row's practiceRevenue keys must be a subset of the practices list
        for (MonthlyRevenuePracticeDataPoint m : result.months()) {
            assertNotNull(m.monthKey());
            assertEquals(6, m.monthKey().length());
            assertNotNull(m.practiceRevenue());
            assertTrue(result.practices().containsAll(m.practiceRevenue().keySet()),
                    "practiceRevenue keys must be a subset of practices list");
        }
    }

    @Test
    void revenueByPractice_explicitRange_doesNotThrow() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 12, 31);
        RevenuePracticeDTO result = service.revenueByPractice(from, to, null);
        assertNotNull(result);
    }

    @Test
    void revenueByPractice_withCompanyFilter_doesNotThrow() {
        Set<String> companyIds = Set.of("00000000-0000-0000-0000-000000000001");
        RevenuePracticeDTO result = service.revenueByPractice(null, null, companyIds);
        assertNotNull(result);
        assertTrue(result.months().isEmpty(), "filter with random UUIDs should return empty");
        assertTrue(result.practices().isEmpty(), "filter with random UUIDs should return empty");
    }
}
