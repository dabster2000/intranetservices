package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionPeriodDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionPracticeDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeRevenueSegmentDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_DISABLED;
import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_UNAVAILABLE;
import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.QUERY_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CxoPracticeContributionServiceTest {

    private static final Instant COST_GENERATION = Instant.parse("2026-07-15T08:00:00Z");
    private static final String BASIS = "basis-v1";
    private static final String VECTOR = "vector-v1";

    @Test
    void fixedOrderingLabelsAndNoAnchorUseNullRatherThanFalseZero() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        PracticeContributionResponseDTO response = invoke(service, "buildResponse",
                new Class<?>[]{CxoPracticeContributionService.PublicationSnapshot.class,
                        PracticeOperatingCostResponseDTO.class, CostSource.class},
                snapshot(false, BigInteger.ONE), unavailableCost(), CostSource.BOOKED);

        assertEquals("UNAVAILABLE_COST", response.responseStatus());
        assertEquals("SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW", response.responseReason());
        assertNull(response.reportingThroughMonth());
        assertNull(response.currentPeriod());
        assertNull(response.priorPeriod());
        assertNull(response.currentPortfolio());
        assertNull(response.priorPortfolio());
        assertEquals(LocalDate.of(2020, 1, 1), response.revenueHistoryCoverageStart());
        assertEquals(List.of("PM", "BA", "CYB", "DEV", "SA"),
                response.practices().stream().map(PracticeContributionPracticeDTO::practiceId).toList());
        assertEquals(List.of("Project Management", "Business Analysis", "Cybersecurity", "Technology",
                        "Solution Architecture"),
                response.practices().stream().map(PracticeContributionPracticeDTO::label).toList());
        assertEquals(List.of("JK", "UD", "EXTERNAL", "OTHER", "UNASSIGNED"),
                response.revenueOnlySegments().stream().map(PracticeRevenueSegmentDTO::segmentId).toList());
        assertTrue(response.practices().stream().allMatch(row -> row.current() == null && row.prior() == null
                && row.revenueDeltaDkk() == null && row.costDeltaDkk() == null
                && row.contributionDeltaDkk() == null));
        assertTrue(response.revenueOnlySegments().stream().allMatch(row -> row.current() == null
                && row.prior() == null && row.revenueDeltaDkk() == null && row.revenueDeltaPct() == null));
    }

    @Test
    void rowScopedResidualDoesNotContaminateUnrelatedCoreOrRevenueOnlyRows() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        Map<String, CxoPracticeContributionService.SegmentAggregate> segments = new LinkedHashMap<>();
        segments.put("PM", confirmed("100.00"));
        segments.put("JK", confirmed("40.00"));
        segments.put("UNASSIGNED", partial("20.00"));
        CxoPracticeContributionService.PeriodAggregate period = period(segments, 0, 0, 0, 0,
                1, 0, 0, 0, 0);

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.SegmentMetrics jk = invoke(service, "segmentMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class}, "JK", period);

        assertEquals("CONFIRMED", pm.dto().contributionStatus());
        assertEquals("CONFIRMED", pm.dto().attributionStatus());
        assertEquals("100.00", pm.dto().netAttributedRevenueDkk());
        assertEquals("50.00", pm.dto().contributionDkk());
        assertNull(pm.dto().availabilityReason());
        assertEquals("CONFIRMED", jk.dto().revenueDisplayStatus());
        assertEquals("40.00", jk.dto().displayRevenueDkk());
        assertNull(jk.dto().availabilityReason());
    }

    @Test
    void provisionalRevenueIsEvidenceOnlyAndScopedToItsSegment() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        Map<String, CxoPracticeContributionService.SegmentAggregate> segments = Map.of(
                "PM", confirmed("100.00"),
                "JK", provisional("25.00"));
        CxoPracticeContributionService.PeriodAggregate period = period(segments, 0, 0, 1, 0,
                0, 0, 0, 0, 0);

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.SegmentMetrics jk = invoke(service, "segmentMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class}, "JK", period);
        PracticeContributionPeriodDTO contributionPeriod = invoke(service, "toPeriod",
                new Class<?>[]{LocalDate.class, LocalDate.class,
                        CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostResponseDTO.class, boolean.class, boolean.class},
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1), period, completeCost(), true, true);

        assertEquals("CONFIRMED", pm.dto().contributionStatus());
        assertEquals("100.00", pm.dto().netAttributedRevenueDkk());
        assertNull(pm.dto().provisionalNetAttributedRevenueDkk());
        assertEquals("UNAVAILABLE", jk.dto().revenueDisplayStatus());
        assertNull(jk.dto().displayRevenueDkk());
        assertNull(jk.dto().netAttributedRevenueDkk());
        assertEquals("25.00", jk.dto().provisionalNetAttributedRevenueDkk());
        assertEquals("PROVISIONAL_NATIVE_DKK", jk.dto().availabilityReason());
        assertEquals("CONFIRMED", contributionPeriod.contributionStatus(),
                "Revenue-only provisional evidence must not change the core-practice contribution status");
        assertEquals("PROVISIONAL", contributionPeriod.fxStatus());
    }

    @Test
    void signedCancellationCannotHideEstimatedOrUnassignedExposure() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = period(Map.of(
                        "PM", cancelledEstimated("200.00"),
                        "JK", cancelledUnassigned("200.00")),
                0, 0, 0, 0, 0, 0, 0, 0, 0);

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.SegmentMetrics jk = invoke(service, "segmentMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class}, "JK", period);
        PracticeContributionPeriodDTO dto = invoke(service, "toPeriod",
                new Class<?>[]{LocalDate.class, LocalDate.class,
                        CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostResponseDTO.class, boolean.class, boolean.class},
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1),
                period, completeCost(), true, true);

        assertEquals("ESTIMATED_ATTRIBUTION", pm.dto().contributionStatus());
        assertEquals("ESTIMATED", pm.dto().attributionStatus());
        assertEquals("PARTIAL", jk.dto().revenueDisplayStatus());
        assertEquals("PARTIAL", jk.dto().attributionStatus());
        assertEquals("ESTIMATED_ATTRIBUTION", dto.contributionStatus(),
                "Revenue-only unassigned evidence must not contaminate the core contribution status");
        assertEquals("50.0000", dto.evidence().unassignedCoveragePct());
        assertEquals("0.00", dto.evidence().unassignedRevenueDkk());
    }

    @Test
    void unavailableReasonUsesSourceBeforeValuationAndNeverInventsZero() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = period(Map.of("PM", confirmed("100.00")),
                1, 1, 0, 0, 0, 0, 0, 0, 0);
        CxoPracticeContributionService.CoreMetrics metrics = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);

        assertEquals("UNAVAILABLE_REVENUE", metrics.dto().contributionStatus());
        assertEquals("SOURCE_DUPLICATE_RISK", metrics.dto().availabilityReason());
        assertNull(metrics.dto().netAttributedRevenueDkk());
        assertNull(metrics.dto().contributionDkk());
        assertEquals("100.00", metrics.dto().confirmedAttributedRevenueDkk());
    }

    @Test
    void fallbackExplanationsIgnoreRoutineRegisteredEvidenceAndCloseToSixCodes() {
        assertEquals("NO_FALLBACK", period(Map.of(), 0, 0, 0, 0, 4, 3, 0, 0, 0)
                .attributionExplanationCode());
        assertEquals("HISTORICAL_PRACTICE_FALLBACK", period(Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 2)
                .attributionExplanationCode());
        assertEquals("REVENUE_SCHEDULED_CAPACITY_FALLBACK", period(Map.of(), 0, 0, 0, 0, 0, 0, 2, 0, 0)
                .attributionExplanationCode());
        assertEquals("REVENUE_MONTH_END_PRACTICE_FALLBACK", period(Map.of(), 0, 0, 0, 0, 0, 0, 0, 2, 0)
                .attributionExplanationCode());
        assertEquals("MULTIPLE_FALLBACK_METHODS", period(Map.of(), 0, 0, 0, 0, 0, 0, 2, 0, 1)
                .attributionExplanationCode());
    }

    @Test
    void deltaMathPreservesSignsAndWithholdsOnlyPriorZeroPercentage() {
        assertEquals("25.00", invokeStatic("moneyDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                new BigDecimal("125"), new BigDecimal("100"), true, true));
        assertEquals("25.0000", invokeStatic("pctDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                new BigDecimal("125"), new BigDecimal("100"), true, true));
        assertEquals("25.0000", invokeStatic("pctDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                new BigDecimal("-75"), new BigDecimal("-100"), true, true));
        assertNull(invokeStatic("pctDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                BigDecimal.ZERO, BigDecimal.ZERO, true, true));
        assertNull(invokeStatic("moneyDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                BigDecimal.ONE, BigDecimal.ONE, true, false));
    }

    @Test
    void latestCertifiedRequestMustBeTerminalAndStableAcrossDoubleRead() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.ONE),
                        snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.ONE)),
                List.of(readyRequestRow(), noChangeRequestRow(), readyRequestRow(), noChangeRequestRow()));

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
        verify(service.costSnapshotProvider, times(2)).getSnapshot(CostSource.BOOKED);
    }

    @Test
    void coherentDoubleReadReturnsStructuredUnavailableCostResponse() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.ONE)),
                List.of(readyRequestRow(), readyRequestRow()));

        PracticeContributionResponseDTO response = service.getContribution(CostSource.BOOKED);

        assertEquals("UNAVAILABLE_COST", response.responseStatus());
        verify(service.costSnapshotProvider).getSnapshot(CostSource.BOOKED);
    }

    @Test
    void secondPublicationMismatchFailsClosedAfterOneRetry() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.TWO),
                        snapshotRow(BigInteger.valueOf(3)), snapshotRow(BigInteger.valueOf(4))),
                List.of(readyRequestRow(), readyRequestRow(), readyRequestRow(), readyRequestRow()));

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
        verify(service.costSnapshotProvider, times(2)).getSnapshot(CostSource.BOOKED);
    }

    @Test
    void disabledPublicationAndAdvancedWatermarkNeverReachCostProvider() {
        CxoPracticeContributionService disabled = new CxoPracticeContributionService();
        disabled.em = queryManager(CxoPracticeContributionService.SNAPSHOT_SQL,
                Collections.singletonList(snapshotRow(false, BigInteger.ONE)));
        disabled.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);

        ContributionUnavailableException disabledError = assertThrows(ContributionUnavailableException.class,
                () -> disabled.getContribution(CostSource.BOOKED));
        assertEquals(PUBLICATION_DISABLED, disabledError.getMessage());
        verify(disabled.costSnapshotProvider, never()).getSnapshot(any());

        CxoPracticeContributionService advanced = coherentNoAnchorService(
                Collections.singletonList(snapshotRow(BigInteger.ONE)), List.of());
        Query watermark = mock(Query.class);
        when(watermark.setHint(any(String.class), any())).thenReturn(watermark);
        when(watermark.getResultList()).thenReturn(watermarkRows(BigInteger.TWO));
        when(advanced.em.createNativeQuery(CxoPracticeContributionService.WATERMARK_SQL)).thenReturn(watermark);

        ContributionUnavailableException advancedError = assertThrows(ContributionUnavailableException.class,
                () -> advanced.getContribution(CostSource.BOOKED));
        assertEquals(PUBLICATION_UNAVAILABLE, advancedError.getMessage());
        verify(advanced.costSnapshotProvider, never()).getSnapshot(any());
    }

    @Test
    void unexpectedSourceWatermarkFailsTheExactPublicationVectorClosed() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                Collections.singletonList(snapshotRow(BigInteger.ONE)), List.of());
        Query watermark = mock(Query.class);
        when(watermark.setHint(any(String.class), any())).thenReturn(watermark);
        List<Object[]> rows = new ArrayList<>(watermarkRows(BigInteger.ONE));
        rows.add(new Object[]{"UNEXPECTED_SOURCE", BigInteger.ONE, "FAILED"});
        when(watermark.getResultList()).thenReturn(rows);
        when(service.em.createNativeQuery(CxoPracticeContributionService.WATERMARK_SQL)).thenReturn(watermark);

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
        verify(service.costSnapshotProvider, never()).getSnapshot(any());
    }

    @Test
    void queryTimeoutMapsToOnlyTheSafeTimeoutCode() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = mock(EntityManager.class);
        service.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);
        when(service.em.createNativeQuery(CxoPracticeContributionService.SNAPSHOT_SQL))
                .thenThrow(new QueryTimeoutFailure());

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(QUERY_TIMEOUT, error.getMessage());
        verify(service.costSnapshotProvider, never()).getSnapshot(any());
    }

    private static CxoPracticeContributionService coherentNoAnchorService(
            List<Object[]> snapshots, List<Object[]> requests) {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = mock(EntityManager.class);
        service.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);
        List<Object[]> remainingSnapshots = new ArrayList<>(snapshots);
        List<Object[]> remainingRequests = new ArrayList<>(requests);

        Query snapshot = mock(Query.class);
        when(snapshot.setHint(any(String.class), any())).thenReturn(snapshot);
        when(snapshot.getResultList()).thenAnswer(
                invocation -> Collections.singletonList(remainingSnapshots.removeFirst()));
        when(service.em.createNativeQuery(CxoPracticeContributionService.SNAPSHOT_SQL)).thenReturn(snapshot);

        Query watermark = mock(Query.class);
        when(watermark.setHint(any(String.class), any())).thenReturn(watermark);
        when(watermark.getResultList()).thenReturn(watermarkRows(BigInteger.ONE));
        when(service.em.createNativeQuery(CxoPracticeContributionService.WATERMARK_SQL)).thenReturn(watermark);

        Query request = mock(Query.class);
        when(request.setParameter(any(String.class), any())).thenReturn(request);
        when(request.setHint(any(String.class), any())).thenReturn(request);
        if (!remainingRequests.isEmpty()) {
            when(request.getResultList()).thenAnswer(
                    invocation -> Collections.singletonList(remainingRequests.removeFirst()));
        }
        when(service.em.createNativeQuery(CxoPracticeContributionService.LATEST_REQUEST_SQL)).thenReturn(request);
        when(service.costSnapshotProvider.getSnapshot(CostSource.BOOKED))
                .thenReturn(new PracticeCostSnapshotProvider.Snapshot(unavailableCost(), false));
        return service;
    }

    private static EntityManager queryManager(String sql, List<Object[]> rows) {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(query.setHint(any(String.class), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        when(em.createNativeQuery(sql)).thenReturn(query);
        return em;
    }

    private static List<Object[]> watermarkRows(BigInteger version) {
        return sourceVersions().keySet().stream()
                .map(name -> new Object[]{name, version, "READY"})
                .toList();
    }

    private static Object[] snapshotRow(BigInteger publicationVersion) {
        return snapshotRow(true, publicationVersion);
    }

    private static Object[] snapshotRow(boolean serving, BigInteger publicationVersion) {
        Object[] row = new Object[43];
        row[0] = serving;
        row[1] = BigInteger.ONE;
        row[2] = "READY";
        row[3] = "revenue-generation";
        row[4] = COST_GENERATION;
        row[5] = BASIS;
        row[6] = BigInteger.valueOf(7);
        for (int i = 7; i <= 15; i++) row[i] = BigInteger.ONE;
        row[16] = false;
        row[17] = "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW";
        row[23] = false;
        row[24] = "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW";
        row[30] = Instant.parse("2026-07-15T09:00:00Z");
        row[31] = Instant.parse("2026-07-15T08:30:00Z");
        row[32] = publicationVersion;
        row[33] = "READY";
        row[35] = COST_GENERATION;
        row[36] = Instant.parse("2026-07-15T08:15:00Z");
        row[37] = BASIS;
        row[38] = BigInteger.ONE;
        row[39] = VECTOR;
        row[40] = BigInteger.ONE;
        row[41] = VECTOR;
        row[42] = LocalDate.of(2020, 1, 1);
        return row;
    }

    private static Object[] readyRequestRow() {
        return new Object[]{BigInteger.ONE, "READY", "key", VECTOR,
                COST_GENERATION, null, BASIS, null, null};
    }

    private static Object[] noChangeRequestRow() {
        return new Object[]{BigInteger.ONE, "NO_CHANGE", "key", VECTOR,
                null, COST_GENERATION, null, BASIS, null};
    }

    private static CxoPracticeContributionService.PublicationSnapshot snapshot(
            boolean available, BigInteger publicationVersion) {
        LocalDate anchor = available ? LocalDate.of(2026, 6, 1) : null;
        return new CxoPracticeContributionService.PublicationSnapshot(
                true, BigInteger.ONE, "READY", "revenue-generation", COST_GENERATION, BASIS,
                BigInteger.valueOf(7), sourceVersions(),
                new CxoPracticeContributionService.SelectedWindow(available,
                        available ? null : "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW",
                        anchor, available ? LocalDate.of(2025, 7, 1) : null,
                        available ? LocalDate.of(2026, 6, 1) : null,
                        available ? LocalDate.of(2024, 7, 1) : null,
                        available ? LocalDate.of(2025, 6, 1) : null),
                Instant.parse("2026-07-15T09:00:00Z"), Instant.parse("2026-07-15T08:30:00Z"),
                publicationVersion, "READY", null, COST_GENERATION,
                Instant.parse("2026-07-15T08:15:00Z"), BASIS,
                BigInteger.ONE, VECTOR, BigInteger.ONE, VECTOR,
                LocalDate.of(2020, 1, 1));
    }

    private static Map<String, BigInteger> sourceVersions() {
        Map<String, BigInteger> versions = new LinkedHashMap<>();
        for (String name : List.of("INVOICE_DOCUMENT", "FINANCE_GL", "CURRENCY",
                "ACCOUNT_CLASSIFICATION", "INVOICE_ATTRIBUTION", "SELF_BILLED",
                "PHANTOM_ATTRIBUTION", "DELIVERY_EVIDENCE", "PRACTICE_BASIS_INPUT")) {
            versions.put(name, BigInteger.ONE);
        }
        return versions;
    }

    private static PracticeOperatingCostResponseDTO completeCost() {
        return costResponse(true, "202606", List.of(costRow("PM", 30, 20, 25, 15)));
    }

    private static PracticeOperatingCostResponseDTO unavailableCost() {
        return costResponse(false, null, List.of());
    }

    private static PracticeOperatingCostResponseDTO costResponse(
            boolean complete, String anchor, List<PracticeOperatingCostDTO> rows) {
        int cells = complete ? 60 : 0;
        return new PracticeOperatingCostResponseDTO(
                "BOOKED", anchor,
                complete ? "202507" : null, complete ? "202606" : null,
                complete ? "202407" : null, complete ? "202506" : null,
                complete ? Instant.parse("2026-07-15T08:00:00Z") : null,
                complete ? 12 : 0, complete ? 12 : 0, complete ? 12 : 0,
                complete ? 12 : 0, complete ? 12 : 0, complete ? 12 : 0,
                cells, cells, cells, 0, 0,
                cells, cells, cells, 0, 0,
                cells, cells, 0, cells, cells, 0,
                complete, complete, complete ? "COMPLETE" : "UNAVAILABLE",
                complete, complete, complete ? "COMPLETE" : "UNAVAILABLE",
                complete ? "COMPLETE" : "UNAVAILABLE", complete,
                "EFFECTIVE_DATED_PRACTICE", complete ? LocalDate.of(2020, 1, 1) : null,
                null, rows);
    }

    private static PracticeOperatingCostDTO costRow(String id, double currentSalary, double currentOpex,
                                                    double priorSalary, double priorOpex) {
        double current = currentSalary + currentOpex;
        double prior = priorSalary + priorOpex;
        return new PracticeOperatingCostDTO(id, currentSalary, currentOpex, current,
                priorSalary, priorOpex, prior, current - prior,
                prior == 0 ? null : (current - prior) / Math.abs(prior) * 100,
                1.0, 1.0, current, prior, current - prior,
                prior == 0 ? null : (current - prior) / Math.abs(prior) * 100);
    }

    private static CxoPracticeContributionService.SegmentAggregate confirmed(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new CxoPracticeContributionService.SegmentAggregate(value, BigDecimal.ZERO, value,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, value.abs(), value.abs(), value.abs(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static CxoPracticeContributionService.SegmentAggregate provisional(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new CxoPracticeContributionService.SegmentAggregate(BigDecimal.ZERO, value, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, value.abs(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static CxoPracticeContributionService.SegmentAggregate partial(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new CxoPracticeContributionService.SegmentAggregate(value, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, value, value, value.abs(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, value.abs(), value.abs());
    }

    private static CxoPracticeContributionService.SegmentAggregate cancelledEstimated(String absolute) {
        BigDecimal exposure = new BigDecimal(absolute);
        return new CxoPracticeContributionService.SegmentAggregate(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, exposure, BigDecimal.ZERO, exposure,
                exposure, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static CxoPracticeContributionService.SegmentAggregate cancelledUnassigned(String absolute) {
        BigDecimal exposure = new BigDecimal(absolute);
        return new CxoPracticeContributionService.SegmentAggregate(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, exposure, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, exposure, exposure);
    }

    private static CxoPracticeContributionService.PeriodAggregate period(
            Map<String, CxoPracticeContributionService.SegmentAggregate> segments,
            int missing, int duplicate, int provisionalNative, int provisionalMonthly,
            int registeredValue, int registeredHours, int scheduled, int monthEnd, int historical) {
        BigDecimal confirmed = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::confirmed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimated = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::estimated)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unassigned = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::unassigned)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal partial = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::partialAffected)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAbs = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::totalAbs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal authoritative = segments.values().stream()
                .map(CxoPracticeContributionService.SegmentAggregate::authoritative)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CxoPracticeContributionService.PeriodAggregate(segments, 1, 1, missing == 0 ? 1 : 0,
                missing, duplicate, authoritative, authoritative, 1, authoritative, BigDecimal.ZERO,
                confirmed, estimated, unassigned, partial, totalAbs,
                registeredValue, registeredHours, scheduled, monthEnd, historical,
                provisionalNative, provisionalMonthly);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T> T invokeStatic(String name, Class<?>[] parameterTypes, Object... arguments) {
        return invoke(new CxoPracticeContributionService(), name, parameterTypes, arguments);
    }

    private static final class QueryTimeoutFailure extends RuntimeException {
    }
}
