package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CxoPracticeOperatingCostServiceTest {

    @Test
    void legacyEndpointUsesTheOnlyGateSuppressingAdapter() {
        PracticeCostSnapshotProvider provider = mock(PracticeCostSnapshotProvider.class);
        PracticeCostSnapshotProvider.Snapshot snapshot = mock(PracticeCostSnapshotProvider.Snapshot.class);
        PracticeOperatingCostResponseDTO response = mock(PracticeOperatingCostResponseDTO.class);
        when(provider.getLegacySnapshot(CostSource.BOOKED)).thenReturn(snapshot);
        when(snapshot.servingEnabled()).thenReturn(true);
        when(snapshot.canonical()).thenReturn(mock(PracticeCostSnapshotProvider.CanonicalSnapshot.class));
        when(snapshot.canonical().windowAvailable()).thenReturn(true);
        when(snapshot.response()).thenReturn(response);
        CxoPracticeOperatingCostService service = new CxoPracticeOperatingCostService();
        service.snapshotProvider = provider;

        assertSame(response, service.getOperatingCost(CostSource.BOOKED));
        verify(provider).getLegacySnapshot(CostSource.BOOKED);
        verify(provider, never()).getSnapshot(CostSource.BOOKED);
    }

    @Test
    void certifiedNoWindowReturnsTypedUnavailableWithoutRecomputingFromRequestDate() {
        PracticeCostSnapshotLoader loader = new PracticeCostSnapshotLoader();
        loader.em = mock(jakarta.persistence.EntityManager.class);

        PracticeCostSnapshotProvider.CanonicalSnapshot snapshot =
                loader.readPublishedCanonicalSnapshot(
                        CostSource.BOOKED, "basis", Instant.parse("2026-07-15T08:00:00Z"),
                        LocalDate.of(2021, 1, 1),
                        new PracticeCostSnapshotProvider.CanonicalWindow(false,
                                "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW",
                                null, null, null, null, null));

        assertFalse(snapshot.windowAvailable());
        assertEquals("SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW", snapshot.windowReason());
        assertNull(snapshot.reportingThroughMonthKey());
        verifyNoInteractions(loader.em);
    }

    @Test
    void canonicalSnapshotAppliesTheContributionBoundToEveryAggregationQuery() {
        jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        PracticeCostSnapshotLoader loader = new PracticeCostSnapshotLoader();
        loader.em = em;

        loader.readPublishedCanonicalSnapshot(
                CostSource.BOOKED, "basis", Instant.parse("2026-07-15T08:00:00Z"),
                LocalDate.of(2021, 1, 1),
                new PracticeCostSnapshotProvider.CanonicalWindow(true, null,
                        LocalDate.of(2026, 6, 1), LocalDate.of(2025, 7, 1),
                        LocalDate.of(2026, 6, 1), LocalDate.of(2024, 7, 1),
                        LocalDate.of(2025, 6, 1)), 3_210);

        verify(query, times(3)).setHint("jakarta.persistence.query.timeout", 3_210);
    }

    @Test
    void canonicalSnapshotResamplesShrinkingContributionDeadlineBeforeEveryAggregationQuery() {
        jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        PracticeCostSnapshotLoader loader = new PracticeCostSnapshotLoader();
        loader.em = em;

        loader.readPublishedCanonicalSnapshot(
                CostSource.BOOKED, "basis", Instant.parse("2026-07-15T08:00:00Z"),
                LocalDate.of(2021, 1, 1),
                new PracticeCostSnapshotProvider.CanonicalWindow(true, null,
                        LocalDate.of(2026, 6, 1), LocalDate.of(2025, 7, 1),
                        LocalDate.of(2026, 6, 1), LocalDate.of(2024, 7, 1),
                        LocalDate.of(2025, 6, 1)), sequencedTimeouts(3_210, 2_100, 900));

        verify(query).setHint("jakarta.persistence.query.timeout", 3_210);
        verify(query).setHint("jakarta.persistence.query.timeout", 2_100);
        verify(query).setHint("jakarta.persistence.query.timeout", 900);
    }

    @Test
    void canonicalSnapshotPreservesTheTypedRequestDeadlineFailure() {
        jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        PracticeCostSnapshotLoader loader = new PracticeCostSnapshotLoader();
        loader.em = em;
        ContributionUnavailableException deadline = new ContributionUnavailableException(
                ContributionUnavailableException.QUERY_TIMEOUT);

        ContributionUnavailableException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ContributionUnavailableException.class,
                () -> loader.readPublishedCanonicalSnapshot(
                        CostSource.BOOKED, "basis", Instant.parse("2026-07-15T08:00:00Z"),
                        LocalDate.of(2021, 1, 1),
                        new PracticeCostSnapshotProvider.CanonicalWindow(true, null,
                                LocalDate.of(2026, 6, 1), LocalDate.of(2025, 7, 1),
                                LocalDate.of(2026, 6, 1), LocalDate.of(2024, 7, 1),
                                LocalDate.of(2025, 6, 1)),
                        () -> { throw deadline; }));

        assertSame(deadline, thrown);
    }

    @Test
    void canonicalSnapshotPreservesARealQueryTimeoutAsTheSafeWrapperCause() {
        jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        jakarta.persistence.QueryTimeoutException timeout =
                new jakarta.persistence.QueryTimeoutException("database deadline");
        when(query.setHint(anyString(), any())).thenThrow(timeout);
        PracticeCostSnapshotLoader loader = new PracticeCostSnapshotLoader();
        loader.em = em;

        jakarta.ws.rs.ServiceUnavailableException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        jakarta.ws.rs.ServiceUnavailableException.class,
                        () -> loader.readPublishedCanonicalSnapshot(
                                CostSource.BOOKED, "basis", Instant.parse("2026-07-15T08:00:00Z"),
                                LocalDate.of(2021, 1, 1),
                                new PracticeCostSnapshotProvider.CanonicalWindow(true, null,
                                        LocalDate.of(2026, 6, 1), LocalDate.of(2025, 7, 1),
                                        LocalDate.of(2026, 6, 1), LocalDate.of(2024, 7, 1),
                                        LocalDate.of(2025, 6, 1)), 3_210));

        assertSame(timeout, thrown.getCause());
    }

    @Test
    void toDto_usesSignedCostsAndMonthlyAverageFte() {
        PracticeCostSnapshotLoader.PracticeAccumulator acc =
                new PracticeCostSnapshotLoader.PracticeAccumulator();
        acc.currentSalary = new BigDecimal("1200.00");
        acc.currentOpex = new BigDecimal("-200.00");
        acc.priorSalary = new BigDecimal("900.00");
        acc.priorOpex = new BigDecimal("100.00");
        acc.currentFteSum = new BigDecimal("120.000000");
        acc.priorFteSum = new BigDecimal("60.000000");

        PracticeOperatingCostDTO dto = PracticeCostSnapshotLoader.toDto("PM", acc);

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
    void zeroFilledLegacyOpexMonthLeavesOperatingCostDtoFieldsCompatible() {
        // A legacy OPEX month whose SUM(opex_amount_dkk) was SQL NULL is now zero-filled to
        // BigDecimal.ZERO before accumulation. It contributes the additive identity, so ordinary
        // legacy operating-cost DTO fields remain semantically unchanged.
        PracticeCostSnapshotLoader.PracticeAccumulator acc =
                new PracticeCostSnapshotLoader.PracticeAccumulator();
        acc.currentSalary = new BigDecimal("1200.00");
        acc.currentOpex = BigDecimal.ZERO.add(new BigDecimal("300.00"));
        acc.priorSalary = new BigDecimal("900.00");
        acc.priorOpex = new BigDecimal("100.00");
        acc.currentFteSum = new BigDecimal("120.000000");
        acc.priorFteSum = new BigDecimal("60.000000");

        PracticeOperatingCostDTO dto = PracticeCostSnapshotLoader.toDto("PM", acc);

        assertEquals(1_200.0, dto.currentSalaryDkk(), 1e-9);
        assertEquals(300.0, dto.currentOpexDkk(), 1e-9);
        assertEquals(1_500.0, dto.currentTotalDkk(), 1e-9);
        assertEquals(1_000.0, dto.priorTotalDkk(), 1e-9);
        assertEquals(10.0, dto.currentAverageFte(), 1e-9);
        assertEquals(5.0, dto.priorAverageFte(), 1e-9);
    }

    @Test
    void toDto_returnsNullRatiosWhenPriorCostOrFteIsZero() {
        PracticeCostSnapshotLoader.PracticeAccumulator acc =
                new PracticeCostSnapshotLoader.PracticeAccumulator();
        acc.currentSalary = new BigDecimal("100.00");
        acc.currentFteSum = new BigDecimal("12.000000");

        PracticeOperatingCostDTO dto = PracticeCostSnapshotLoader.toDto("BA", acc);

        assertNull(dto.totalDeltaPct());
        assertEquals(100.0, dto.currentCostPerFteDkk(), 1e-9);
        assertNull(dto.priorCostPerFteDkk());
        assertNull(dto.costPerFteDeltaDkk());
        assertNull(dto.costPerFteDeltaPct());
    }

    @Test
    void canonicalMathNeverRoundTripsThroughBinaryFloatingPoint() {
        PracticeCostSnapshotLoader.PracticeAccumulator acc =
                new PracticeCostSnapshotLoader.PracticeAccumulator();
        acc.currentSalary = new BigDecimal("0.10");
        acc.currentOpex = new BigDecimal("0.20");
        acc.currentFteSum = new BigDecimal("12.000000");
        PracticeCostSnapshotLoader.CoverageResult complete =
                new PracticeCostSnapshotLoader.CoverageResult(12, 12, 12, 0, 0, true);

        PracticeCostSnapshotProvider.CanonicalPractice row =
                PracticeCostSnapshotLoader.toCanonical("PM", acc, complete, complete);

        assertEquals(0, new BigDecimal("0.30").compareTo(row.currentTotalDkk()));
        assertEquals(0, new BigDecimal("0.30").compareTo(row.currentCostPerFteDkk()));
    }

    @Test
    void reportingWindowUsesCompleteMetadataAnchorAndTwoAdjacentTtms() {
        PracticeCostSnapshotLoader.OperatingWindow window =
                PracticeCostSnapshotLoader.reportingWindow(YearMonth.of(2026, 3));

        assertEquals("202603", window.reportingThroughMonthKey());
        assertEquals("202504", window.currentStartMonthKey());
        assertEquals("202603", window.currentEndMonthKey());
        assertEquals("202404", window.priorStartMonthKey());
        assertEquals("202503", window.priorEndMonthKey());
        assertEquals(YearMonth.of(2021, 8),
                PracticeCostSnapshotLoader.metadataStartMonth(YearMonth.of(2026, 6)));
    }

    @Test
    void coverageRequiresExactCompanyPracticeMonthSetEquality() {
        Set<String> expected = Set.of(
                "technology:PM:202604", "technology:BA:202604",
                "consulting:PM:202604", "consulting:DEV:202604");

        PracticeCostSnapshotLoader.CoverageResult missingCompany =
                PracticeCostSnapshotLoader.coverage(expected, Set.of(
                        "technology:PM:202604", "technology:BA:202604"));
        assertEquals(4, missingCompany.expectedCount());
        assertEquals(2, missingCompany.actualCount());
        assertEquals(2, missingCompany.coveredCount());
        assertEquals(2, missingCompany.missingCount());
        assertEquals(0, missingCompany.unexpectedCount());
        assertFalse(missingCompany.complete());

        PracticeCostSnapshotLoader.CoverageResult exact =
                PracticeCostSnapshotLoader.coverage(expected, expected);
        assertTrue(exact.complete());

        PracticeCostSnapshotLoader.CoverageResult swapped =
                PracticeCostSnapshotLoader.coverage(expected, Set.of(
                        "technology:PM:202604", "technology:BA:202604",
                        "consulting:PM:202604", "cyber:DEV:202604"));
        assertEquals(1, swapped.missingCount());
        assertEquals(1, swapped.unexpectedCount());
        assertFalse(swapped.complete());
    }

    @Test
    void latestCompleteAnchorSearchesBackwardAndRequiresTwelveCompleteCompanyMonths() {
        List<PracticeCostSnapshotLoader.SalaryCompletenessCell> cells = completeMetadata(
                YearMonth.of(2025, 4), 12);

        assertEquals(YearMonth.of(2026, 3),
                PracticeCostSnapshotLoader.latestCompleteAnchor(
                                cells, YearMonth.of(2026, 6))
                        .orElseThrow());

        cells.remove(0);
        assertTrue(PracticeCostSnapshotLoader.latestCompleteAnchor(
                cells, YearMonth.of(2026, 6)).isEmpty());
    }

    @Test
    void currentAndPriorCostCompletenessAreEvaluatedIndependently() {
        List<PracticeCostSnapshotLoader.SalaryCompletenessCell> cells = completeMetadata(
                YearMonth.of(2024, 4), 24);
        PracticeCostSnapshotLoader.SalaryCompletenessCell first = cells.get(0);
        cells.set(0, new PracticeCostSnapshotLoader.SalaryCompletenessCell(
                first.companyId(), first.monthKey(),
                first.expectedSalaryCellCount(), first.actualSalaryCellCount(),
                first.coveredSalaryCellCount(), first.missingSalaryCellCount(),
                first.unexpectedSalaryCellCount(), false, 0));

        PracticeCostSnapshotLoader.PeriodCostCompleteness current =
                PracticeCostSnapshotLoader.summarizeCostCompleteness(
                        cells, "202504", "202603");
        PracticeCostSnapshotLoader.PeriodCostCompleteness prior =
                PracticeCostSnapshotLoader.summarizeCostCompleteness(
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
        Set<String> expected = PracticeCostSnapshotLoader.expectedPracticeMonthCells(
                "202504", "202603");
        Set<String> actual = new HashSet<>(expected);
        actual.remove("SA:202603");

        PracticeCostSnapshotLoader.CoverageResult coverage =
                PracticeCostSnapshotLoader.coverage(expected, actual);

        assertEquals(60, coverage.expectedCount());
        assertEquals(59, coverage.coveredCount());
        assertEquals(1, coverage.missingCount());
        assertFalse(coverage.complete());
    }

    @Test
    void perPracticeFteCoverageDoesNotTurnOneMissingCellIntoTwelveMissingCellsEverywhere() {
        Set<String> pmExpected = PracticeCostSnapshotLoader.expectedPracticeMonthCells(
                "PM", "202504", "202603");
        Set<String> actual = new HashSet<>(PracticeCostSnapshotLoader.expectedPracticeMonthCells(
                "202504", "202603"));
        actual.remove("SA:202603");

        PracticeCostSnapshotLoader.CoverageResult pm =
                PracticeCostSnapshotLoader.coverage(pmExpected, actual);
        PracticeCostSnapshotLoader.CoverageResult sa = PracticeCostSnapshotLoader.coverage(
                PracticeCostSnapshotLoader.expectedPracticeMonthCells("SA", "202504", "202603"), actual);

        assertEquals(12, pm.coveredCount());
        assertEquals(0, pm.missingCount());
        assertEquals(11, sa.coveredCount());
        assertEquals(1, sa.missingCount());
    }

    @Test
    void costMonthEndFallbackCountsRemainPeriodSpecific() {
        List<PracticeCostSnapshotLoader.SalaryCompletenessCell> cells = completeMetadata(
                YearMonth.of(2024, 4), 24);
        PracticeCostSnapshotLoader.SalaryCompletenessCell current = cells.getLast();
        cells.set(cells.size() - 1, new PracticeCostSnapshotLoader.SalaryCompletenessCell(
                current.companyId(), current.monthKey(), current.expectedSalaryCellCount(),
                current.actualSalaryCellCount(), current.coveredSalaryCellCount(),
                current.missingSalaryCellCount(), current.unexpectedSalaryCellCount(), true, 3));

        assertEquals(3, PracticeCostSnapshotLoader.summarizeCostCompleteness(
                cells, "202504", "202603").costMonthEndFallbackEmployeeMonthCount());
        assertEquals(0, PracticeCostSnapshotLoader.summarizeCostCompleteness(
                cells, "202404", "202503").costMonthEndFallbackEmployeeMonthCount());
    }

    @Test
    void completenessStatusDistinguishesSalaryAndFteFailures() {
        assertEquals("COMPLETE", PracticeCostSnapshotLoader.completenessStatus(true, true));
        assertEquals("INCOMPLETE_SALARY_COVERAGE",
                PracticeCostSnapshotLoader.completenessStatus(false, true));
        assertEquals("INCOMPLETE_FTE_COVERAGE",
                PracticeCostSnapshotLoader.completenessStatus(true, false));
        assertEquals("INCOMPLETE_SALARY_AND_FTE_COVERAGE",
                PracticeCostSnapshotLoader.completenessStatus(false, false));
    }

    @Test
    void costQueryIsBoundToSelectedPostingStatusesAndOperatingCostTypes() {
        String sql = PracticeCostSnapshotLoader.COST_ROWS_SQL;
        assertTrue(sql.contains("company_id IN (:companies)"));
        assertTrue(sql.contains("posting_status IN (:postingStatuses)"));
        assertTrue(sql.contains("cost_type IN ('SALARIES', 'OPEX')"));
        assertFalse(sql.contains("REVENUE"));
        assertEquals(Set.of("BOOKED"),
                Set.copyOf(dk.trustworks.intranet.financeservice.model.enums.CostSource.BOOKED.postingStatusNames()));
        assertEquals(Set.of("BOOKED", "DRAFT"),
                Set.copyOf(dk.trustworks.intranet.financeservice.model.enums.CostSource.BOOKED_PLUS_DRAFT.postingStatusNames()));
        assertTrue(PracticeCostSnapshotLoader.SALARY_COMPLETENESS_ROWS_SQL.contains(
                "FROM fact_practice_salary_completeness_mat"));
        assertTrue(PracticeCostSnapshotLoader.SALARY_COMPLETENESS_ROWS_SQL.contains(
                "rule_version = :ruleVersion"));
        assertTrue(PracticeCostSnapshotLoader.PUBLICATION_SNAPSHOT_SQL.contains(
                "COUNT(materialized_at) AS timestamped_rows"));
        assertFalse(PracticeCostSnapshotLoader.PUBLICATION_SNAPSHOT_SQL.contains(
                "bi_refresh_watermark"),
                "coherent cost evidence remains independent of actual-data refresh failures");
    }

    @Test
    void publicationValidationAcceptsOnlyOneCompleteReadyGeneration() {
        Instant generation = Instant.parse("2026-07-14T08:30:00.123456Z");
        PracticeCostSnapshotLoader.PublicationSnapshot valid = publication(
                "READY", null, generation,
                source(100, generation), source(60, generation), source(360, generation));

        assertNull(PracticeCostSnapshotLoader.publicationValidationFailure(valid));

        PracticeCostSnapshotLoader.PublicationSnapshot empty = publication(
                "READY", null, generation,
                source(0, generation), source(60, generation), source(360, generation));
        assertEquals("operating-cost source is empty",
                PracticeCostSnapshotLoader.publicationValidationFailure(empty));

        PracticeCostSnapshotLoader.PublicationSnapshot wrongCount =
                new PracticeCostSnapshotLoader.PublicationSnapshot(
                        "READY", null, generation, generation.plusMillis(1),
                        101, 60, 360,
                        source(100, generation), source(60, generation), source(360, generation));
        assertEquals("operating-cost row count does not match publication",
                PracticeCostSnapshotLoader.publicationValidationFailure(wrongCount));

        PracticeCostSnapshotLoader.SourcePublication partiallyStamped =
                new PracticeCostSnapshotLoader.SourcePublication(
                        100, 99, generation, generation);
        PracticeCostSnapshotLoader.PublicationSnapshot partial = publication(
                "READY", null, generation,
                partiallyStamped, source(60, generation), source(360, generation));
        assertEquals("operating-cost contains unpublished rows",
                PracticeCostSnapshotLoader.publicationValidationFailure(partial));

        Instant otherGeneration = generation.plusSeconds(1);
        PracticeCostSnapshotLoader.PublicationSnapshot mismatch = publication(
                "READY", null, generation,
                source(100, otherGeneration), source(60, generation), source(360, generation));
        assertEquals("operating-cost generation does not match publication",
                PracticeCostSnapshotLoader.publicationValidationFailure(mismatch));
    }

    @Test
    void publicationValidationRejectsRunningFailedAndActiveRefreshStates() {
        Instant generation = Instant.parse("2026-07-14T08:30:00Z");

        assertEquals("publication is not READY",
                PracticeCostSnapshotLoader.publicationValidationFailure(publication(
                        "RUNNING", null, generation,
                        source(100, generation), source(60, generation), source(360, generation))));
        assertEquals("publication is not READY",
                PracticeCostSnapshotLoader.publicationValidationFailure(publication(
                        "FAILED", null, generation,
                        source(100, generation), source(60, generation), source(360, generation))));
        assertEquals("publication token is still active",
                PracticeCostSnapshotLoader.publicationValidationFailure(publication(
                        "READY", "active-token", generation,
                        source(100, generation), source(60, generation), source(360, generation))));
    }

    @Test
    void preAndPostPublicationMustRemainIdentical() {
        Instant firstGeneration = Instant.parse("2026-07-14T08:30:00Z");
        Instant secondGeneration = firstGeneration.plusSeconds(10);
        PracticeCostSnapshotLoader.PublicationSnapshot before = publication(
                "READY", null, firstGeneration,
                source(100, firstGeneration), source(60, firstGeneration), source(360, firstGeneration));
        PracticeCostSnapshotLoader.PublicationSnapshot same = publication(
                "READY", null, firstGeneration,
                source(100, firstGeneration), source(60, firstGeneration), source(360, firstGeneration));
        PracticeCostSnapshotLoader.PublicationSnapshot changed = publication(
                "READY", null, secondGeneration,
                source(100, secondGeneration), source(60, secondGeneration), source(360, secondGeneration));

        assertTrue(PracticeCostSnapshotLoader.samePublication(before, same));
        assertFalse(PracticeCostSnapshotLoader.samePublication(before, changed));
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

    private static List<PracticeCostSnapshotLoader.SalaryCompletenessCell> completeMetadata(
            YearMonth start, int monthCount) {
        List<PracticeCostSnapshotLoader.SalaryCompletenessCell> cells = new ArrayList<>();
        for (int monthOffset = 0; monthOffset < monthCount; monthOffset++) {
            String monthKey = start.plusMonths(monthOffset).toString().replace("-", "");
            for (String company : PracticeCostSnapshotLoader.PRODUCTION_COMPANIES) {
                cells.add(new PracticeCostSnapshotLoader.SalaryCompletenessCell(
                        company, monthKey, 2, 2, 2, 0, 0, true, 0));
            }
        }
        return cells;
    }

    private static IntSupplier sequencedTimeouts(int... values) {
        List<Integer> remaining = new ArrayList<>();
        for (int value : values) remaining.add(value);
        return remaining::removeFirst;
    }

    private static PracticeCostSnapshotLoader.SourcePublication source(
            long rowCount, Instant generation) {
        return new PracticeCostSnapshotLoader.SourcePublication(
                rowCount, rowCount, generation, generation);
    }

    private static PracticeCostSnapshotLoader.PublicationSnapshot publication(
            String publicationState,
            String activeToken,
            Instant generation,
            PracticeCostSnapshotLoader.SourcePublication opex,
            PracticeCostSnapshotLoader.SourcePublication fte,
            PracticeCostSnapshotLoader.SourcePublication completeness) {
        return new PracticeCostSnapshotLoader.PublicationSnapshot(
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
