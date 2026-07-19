package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    void reportingWindowUsesCompleteMetadataAnchorAndTwoAdjacentTtms() {
        CxoPracticeOperatingCostService.OperatingWindow window =
                CxoPracticeOperatingCostService.reportingWindow(YearMonth.of(2026, 3));

        assertEquals("202603", window.reportingThroughMonthKey());
        assertEquals("202504", window.currentStartMonthKey());
        assertEquals("202603", window.currentEndMonthKey());
        assertEquals("202404", window.priorStartMonthKey());
        assertEquals("202503", window.priorEndMonthKey());
        assertEquals(YearMonth.of(2021, 8),
                CxoPracticeOperatingCostService.metadataStartMonth(YearMonth.of(2026, 6)));
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
    void latestCompleteAnchorSearchesBackwardAndRequiresTwelveCompleteCompanyMonths() {
        List<CxoPracticeOperatingCostService.SalaryCompletenessCell> cells = completeMetadata(
                YearMonth.of(2025, 4), 12);

        assertEquals(YearMonth.of(2026, 3),
                CxoPracticeOperatingCostService.latestCompleteAnchor(
                                cells, YearMonth.of(2026, 6))
                        .orElseThrow());

        cells.remove(0);
        assertTrue(CxoPracticeOperatingCostService.latestCompleteAnchor(
                cells, YearMonth.of(2026, 6)).isEmpty());
    }

    @Test
    void currentAndPriorCostCompletenessAreEvaluatedIndependently() {
        List<CxoPracticeOperatingCostService.SalaryCompletenessCell> cells = completeMetadata(
                YearMonth.of(2024, 4), 24);
        CxoPracticeOperatingCostService.SalaryCompletenessCell first = cells.get(0);
        cells.set(0, new CxoPracticeOperatingCostService.SalaryCompletenessCell(
                first.companyId(), first.monthKey(),
                first.expectedSalaryCellCount(), first.actualSalaryCellCount(),
                first.coveredSalaryCellCount(), first.missingSalaryCellCount(),
                first.unexpectedSalaryCellCount(), false));

        CxoPracticeOperatingCostService.PeriodCostCompleteness current =
                CxoPracticeOperatingCostService.summarizeCostCompleteness(
                        cells, "202504", "202603");
        CxoPracticeOperatingCostService.PeriodCostCompleteness prior =
                CxoPracticeOperatingCostService.summarizeCostCompleteness(
                        cells, "202404", "202503");

        assertTrue(current.complete());
        assertEquals(72, current.salaryCoverage().expectedCount());
        assertEquals(72, current.salaryCoverage().coveredCount());
        assertEquals(0, current.salaryCoverage().missingCount());
        assertFalse(prior.complete());
        assertTrue(prior.salaryCoverage().complete(),
                "exact allocation cells do not override a failed aggregate salary rule");
    }

    @Test
    void fteCoverageUsesExactlyFivePracticesAcrossTwelveMonths() {
        Set<String> expected = CxoPracticeOperatingCostService.expectedPracticeMonthCells(
                "202504", "202603");
        Set<String> actual = new HashSet<>(expected);
        actual.remove("SA:202603");

        CxoPracticeOperatingCostService.CoverageResult coverage =
                CxoPracticeOperatingCostService.coverage(expected, actual);

        assertEquals(60, coverage.expectedCount());
        assertEquals(59, coverage.coveredCount());
        assertEquals(1, coverage.missingCount());
        assertFalse(coverage.complete());
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
        assertTrue(sql.contains("company_id IN (:companies)"));
        assertTrue(sql.contains("posting_status IN (:postingStatuses)"));
        assertTrue(sql.contains("cost_type IN ('SALARIES', 'OPEX')"));
        assertFalse(sql.contains("REVENUE"));
        assertEquals(Set.of("BOOKED"),
                Set.copyOf(dk.trustworks.intranet.financeservice.model.enums.CostSource.BOOKED.postingStatusNames()));
        assertEquals(Set.of("BOOKED", "DRAFT"),
                Set.copyOf(dk.trustworks.intranet.financeservice.model.enums.CostSource.BOOKED_PLUS_DRAFT.postingStatusNames()));
        assertTrue(CxoPracticeOperatingCostService.SALARY_COMPLETENESS_ROWS_SQL.contains(
                "FROM fact_practice_salary_completeness_mat"));
        assertTrue(CxoPracticeOperatingCostService.SALARY_COMPLETENESS_ROWS_SQL.contains(
                "rule_version = :ruleVersion"));
        assertTrue(CxoPracticeOperatingCostService.PUBLICATION_SNAPSHOT_SQL.contains(
                "COUNT(materialized_at) AS timestamped_rows"));
        assertFalse(CxoPracticeOperatingCostService.PUBLICATION_SNAPSHOT_SQL.contains(
                "bi_refresh_watermark"),
                "coherent cost evidence remains independent of actual-data refresh failures");
    }

    @Test
    void publicationValidationAcceptsOnlyOneCompleteReadyGeneration() {
        Instant generation = Instant.parse("2026-07-14T08:30:00.123456Z");
        CxoPracticeOperatingCostService.PublicationSnapshot valid = publication(
                "READY", null, generation,
                source(100, generation), source(60, generation), source(360, generation));

        assertNull(CxoPracticeOperatingCostService.publicationValidationFailure(valid));

        CxoPracticeOperatingCostService.PublicationSnapshot empty = publication(
                "READY", null, generation,
                source(0, generation), source(60, generation), source(360, generation));
        assertEquals("operating-cost source is empty",
                CxoPracticeOperatingCostService.publicationValidationFailure(empty));

        CxoPracticeOperatingCostService.PublicationSnapshot wrongCount =
                new CxoPracticeOperatingCostService.PublicationSnapshot(
                        "READY", null, generation, generation.plusMillis(1),
                        101, 60, 360,
                        source(100, generation), source(60, generation), source(360, generation));
        assertEquals("operating-cost row count does not match publication",
                CxoPracticeOperatingCostService.publicationValidationFailure(wrongCount));

        CxoPracticeOperatingCostService.SourcePublication partiallyStamped =
                new CxoPracticeOperatingCostService.SourcePublication(
                        100, 99, generation, generation);
        CxoPracticeOperatingCostService.PublicationSnapshot partial = publication(
                "READY", null, generation,
                partiallyStamped, source(60, generation), source(360, generation));
        assertEquals("operating-cost contains unpublished rows",
                CxoPracticeOperatingCostService.publicationValidationFailure(partial));

        Instant otherGeneration = generation.plusSeconds(1);
        CxoPracticeOperatingCostService.PublicationSnapshot mismatch = publication(
                "READY", null, generation,
                source(100, otherGeneration), source(60, generation), source(360, generation));
        assertEquals("operating-cost generation does not match publication",
                CxoPracticeOperatingCostService.publicationValidationFailure(mismatch));
    }

    @Test
    void publicationValidationRejectsRunningFailedAndActiveRefreshStates() {
        Instant generation = Instant.parse("2026-07-14T08:30:00Z");

        assertEquals("publication is not READY",
                CxoPracticeOperatingCostService.publicationValidationFailure(publication(
                        "RUNNING", null, generation,
                        source(100, generation), source(60, generation), source(360, generation))));
        assertEquals("publication is not READY",
                CxoPracticeOperatingCostService.publicationValidationFailure(publication(
                        "FAILED", null, generation,
                        source(100, generation), source(60, generation), source(360, generation))));
        assertEquals("publication token is still active",
                CxoPracticeOperatingCostService.publicationValidationFailure(publication(
                        "READY", "active-token", generation,
                        source(100, generation), source(60, generation), source(360, generation))));
    }

    @Test
    void preAndPostPublicationMustRemainIdentical() {
        Instant firstGeneration = Instant.parse("2026-07-14T08:30:00Z");
        Instant secondGeneration = firstGeneration.plusSeconds(10);
        CxoPracticeOperatingCostService.PublicationSnapshot before = publication(
                "READY", null, firstGeneration,
                source(100, firstGeneration), source(60, firstGeneration), source(360, firstGeneration));
        CxoPracticeOperatingCostService.PublicationSnapshot same = publication(
                "READY", null, firstGeneration,
                source(100, firstGeneration), source(60, firstGeneration), source(360, firstGeneration));
        CxoPracticeOperatingCostService.PublicationSnapshot changed = publication(
                "READY", null, secondGeneration,
                source(100, secondGeneration), source(60, secondGeneration), source(360, secondGeneration));

        assertTrue(CxoPracticeOperatingCostService.samePublication(before, same));
        assertFalse(CxoPracticeOperatingCostService.samePublication(before, changed));
    }

    @Test
    void sourceFreshnessIsAnUtcInstantContract() {
        assertEquals(Instant.class,
                List.of(PracticeOperatingCostResponseDTO.class.getRecordComponents()).stream()
                        .filter(component -> component.getName().equals("sourceRefreshedAt"))
                        .findFirst()
                        .orElseThrow()
                        .getType());
    }

    private static List<CxoPracticeOperatingCostService.SalaryCompletenessCell> completeMetadata(
            YearMonth start, int monthCount) {
        List<CxoPracticeOperatingCostService.SalaryCompletenessCell> cells = new ArrayList<>();
        for (int monthOffset = 0; monthOffset < monthCount; monthOffset++) {
            String monthKey = start.plusMonths(monthOffset).toString().replace("-", "");
            for (String company : CxoPracticeOperatingCostService.PRODUCTION_COMPANIES) {
                cells.add(new CxoPracticeOperatingCostService.SalaryCompletenessCell(
                        company, monthKey, 2, 2, 2, 0, 0, true));
            }
        }
        return cells;
    }

    private static CxoPracticeOperatingCostService.SourcePublication source(
            long rowCount, Instant generation) {
        return new CxoPracticeOperatingCostService.SourcePublication(
                rowCount, rowCount, generation, generation);
    }

    private static CxoPracticeOperatingCostService.PublicationSnapshot publication(
            String publicationState,
            String activeToken,
            Instant generation,
            CxoPracticeOperatingCostService.SourcePublication opex,
            CxoPracticeOperatingCostService.SourcePublication fte,
            CxoPracticeOperatingCostService.SourcePublication completeness) {
        return new CxoPracticeOperatingCostService.PublicationSnapshot(
                publicationState,
                activeToken,
                generation,
                generation.plusMillis(1),
                opex.totalRowCount(),
                fte.totalRowCount(),
                completeness.totalRowCount(),
                opex,
                fte,
                completeness);
    }
}
