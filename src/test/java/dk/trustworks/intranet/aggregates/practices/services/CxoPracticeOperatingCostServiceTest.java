package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CxoPracticeOperatingCostServiceTest {

    @Test
    void toDto_usesSignedCostsAndMonthlyAverageFte() {
        CxoPracticeOperatingCostService.PracticeAccumulator acc =
                new CxoPracticeOperatingCostService.PracticeAccumulator();
        acc.currentSalary = 1_200.0;
        acc.currentOpex = -200.0;
        acc.priorSalary = 900.0;
        acc.priorOpex = 100.0;
        acc.currentFteSum = 120.0;
        acc.priorFteSum = 60.0;

        PracticeOperatingCostDTO dto = CxoPracticeOperatingCostService.toDto("PM", acc);

        assertEquals(1_000.0, dto.currentTotalDkk(), 1e-9);
        assertEquals(1_000.0, dto.priorTotalDkk(), 1e-9);
        assertEquals(0.0, dto.totalDeltaDkk(), 1e-9);
        assertEquals(0.0, dto.totalDeltaPct(), 1e-9);
        assertEquals(10.0, dto.currentAverageFte(), 1e-9);
        assertEquals(5.0, dto.priorAverageFte(), 1e-9);
        assertEquals(100.0, dto.currentCostPerFteDkk(), 1e-9);
        assertEquals(200.0, dto.priorCostPerFteDkk(), 1e-9);
        assertEquals(-100.0, dto.costPerFteDeltaDkk(), 1e-9);
        assertEquals(-50.0, dto.costPerFteDeltaPct(), 1e-9);
    }

    @Test
    void toDto_returnsNullRatiosWhenPriorCostOrFteIsZero() {
        CxoPracticeOperatingCostService.PracticeAccumulator acc =
                new CxoPracticeOperatingCostService.PracticeAccumulator();
        acc.currentSalary = 100.0;
        acc.currentFteSum = 12.0;

        PracticeOperatingCostDTO dto = CxoPracticeOperatingCostService.toDto("BA", acc);

        assertNull(dto.totalDeltaPct());
        assertEquals(100.0, dto.currentCostPerFteDkk(), 1e-9);
        assertNull(dto.priorCostPerFteDkk());
        assertNull(dto.costPerFteDeltaDkk());
        assertNull(dto.costPerFteDeltaPct());
    }

    @Test
    void reportingWindowUsesConservativeMonthMinusTwoAndTwoAdjacentTtms() {
        CxoPracticeOperatingCostService.OperatingWindow window =
                CxoPracticeOperatingCostService.reportingWindow(LocalDate.of(2026, 7, 14));

        assertEquals("202605", window.reportingThroughMonthKey());
        assertEquals("202506", window.currentStartMonthKey());
        assertEquals("202605", window.currentEndMonthKey());
        assertEquals("202406", window.priorStartMonthKey());
        assertEquals("202505", window.priorEndMonthKey());
        assertEquals("202605", CxoPracticeOperatingCostService.reportingThroughMonthKey(
                LocalDate.of(2026, 7, 1)));
    }

    @Test
    void coverageRequiresExactCompanyPracticeMonthSetEquality() {
        Set<String> expected = Set.of(
                "technology:PM:202604", "technology:BA:202604",
                "consulting:PM:202604", "consulting:DEV:202604");

        CxoPracticeOperatingCostService.CoverageResult missingCompany =
                CxoPracticeOperatingCostService.coverage(expected, Set.of(
                        "technology:PM:202604", "technology:BA:202604"));
        assertEquals(4, missingCompany.expectedCount());
        assertEquals(2, missingCompany.actualCount());
        assertEquals(2, missingCompany.coveredCount());
        assertEquals(2, missingCompany.missingCount());
        assertEquals(0, missingCompany.unexpectedCount());
        assertFalse(missingCompany.complete());

        CxoPracticeOperatingCostService.CoverageResult exact =
                CxoPracticeOperatingCostService.coverage(expected, expected);
        assertTrue(exact.complete());

        CxoPracticeOperatingCostService.CoverageResult swapped =
                CxoPracticeOperatingCostService.coverage(expected, Set.of(
                        "technology:PM:202604", "technology:BA:202604",
                        "consulting:PM:202604", "cyber:DEV:202604"));
        assertEquals(1, swapped.missingCount());
        assertEquals(1, swapped.unexpectedCount());
        assertFalse(swapped.complete());
    }

    @Test
    void completenessStatusDistinguishesSalaryAndFteFailures() {
        assertEquals("COMPLETE", CxoPracticeOperatingCostService.completenessStatus(true, true));
        assertEquals("INCOMPLETE_SALARY_COVERAGE",
                CxoPracticeOperatingCostService.completenessStatus(false, true));
        assertEquals("INCOMPLETE_FTE_COVERAGE",
                CxoPracticeOperatingCostService.completenessStatus(true, false));
        assertEquals("INCOMPLETE_SALARY_AND_FTE_COVERAGE",
                CxoPracticeOperatingCostService.completenessStatus(false, false));
    }

    @Test
    void costQueryIsBoundToSelectedPostingStatusesAndOperatingCostTypes() {
        String sql = CxoPracticeOperatingCostService.COST_ROWS_SQL;
        assertTrue(sql.contains("posting_status IN (:postingStatuses)"));
        assertTrue(sql.contains("cost_type IN ('SALARIES', 'OPEX')"));
        assertFalse(sql.contains("REVENUE"));
        assertEquals(Set.of("BOOKED"),
                Set.copyOf(dk.trustworks.intranet.financeservice.model.enums.CostSource.BOOKED.postingStatusNames()));
        assertEquals(Set.of("BOOKED", "DRAFT"),
                Set.copyOf(dk.trustworks.intranet.financeservice.model.enums.CostSource.BOOKED_PLUS_DRAFT.postingStatusNames()));
    }
}
