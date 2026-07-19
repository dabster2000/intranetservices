package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.batch.BatchScheduler;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.batch.operations.JobOperator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offline coherence contract for the incremental path. It deliberately crosses the reader and
 * scheduler seams: a byte-identical incremental recertification keeps the published response
 * coherent, while non-terminal cost work blocks reads and revenue scheduling remains cost-first.
 */
class PracticeContributionIncrementalRefreshCoherenceIT {

    private static final Instant COST_GENERATION = Instant.parse("2026-07-15T08:00:00Z");
    private static final String BASIS = "basis-v1";
    private static final String REQUEST_KEY = "a".repeat(64);
    private static final String VECTOR = "b".repeat(64);
    private static final BigInteger SOURCE_VERSION = BigInteger.TEN;

    @ParameterizedTest(name = "terminal incremental {0} preserves the coherent published pair")
    @ValueSource(strings = {"READY", "NO_CHANGE"})
    void irrelevantOrPairedIncrementalPreservesPublishedGenerationAndExactSourceVector(
            String terminalStatus) {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = coherentEntityManager(terminalRequest(terminalStatus));
        service.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);
        when(service.costSnapshotProvider.getSnapshot(CostSource.BOOKED))
                .thenReturn(new PracticeCostSnapshotProvider.Snapshot(completeCost(), true));

        PracticeContributionResponseDTO response = service.getContribution(CostSource.BOOKED);

        assertEquals("AVAILABLE", response.responseStatus());
        assertEquals("revenue-generation-stable", response.revenueGenerationId());
        assertEquals(COST_GENERATION, response.pairedCostGenerationAt());
        assertEquals(Set.of(
                        "INVOICE_DOCUMENT", "FINANCE_GL", "CURRENCY", "ACCOUNT_CLASSIFICATION",
                        "INVOICE_ATTRIBUTION", "SELF_BILLED", "PHANTOM_ATTRIBUTION",
                        "DELIVERY_EVIDENCE", "PRACTICE_BASIS_INPUT"),
                response.sourceWatermarkVersions().keySet());
        assertTrue(response.sourceWatermarkVersions().values().stream()
                .allMatch(SOURCE_VERSION.toString()::equals));
        verify(service.costSnapshotProvider).getSnapshot(CostSource.BOOKED);
    }

    @ParameterizedTest(name = "{0} cost work blocks the coherent response")
    @MethodSource("blockingRequests")
    void relevantNonTerminalOrUnresolvedCostWorkFailsClosed(
            String status, BigInteger successorRequestId) {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = coherentEntityManager(request(status, successorRequestId));
        service.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
        verify(service.costSnapshotProvider, never()).getSnapshot(any());
    }

    static Stream<Arguments> blockingRequests() {
        return Stream.of(
                Arguments.of("PENDING", null),
                Arguments.of("RUNNING", null),
                Arguments.of("FAILED", null),
                Arguments.of("SUPERSEDED", BigInteger.valueOf(2)));
    }

    @Test
    void relevantIncrementalContractStartsCostBeforeRevenueAndBlocksUnresolvedStates() {
        BatchScheduler scheduler = new BatchScheduler();
        JobOperator jobs = mock(JobOperator.class);
        EntityManager em = mock(EntityManager.class);
        Query eligible = query();
        OpexDistributionRefreshService signals = mock(OpexDistributionRefreshService.class);
        when(em.createNativeQuery(anyString())).thenReturn(eligible);
        when(eligible.getResultList()).thenReturn(Collections.singletonList(
                new Object[]{BigInteger.ONE, REQUEST_KEY, VECTOR}));
        when(eligible.getSingleResult()).thenReturn(1L);
        when(jobs.getJobNames()).thenReturn(Collections.emptySet());
        setField(scheduler, "jobOperator", jobs);
        setField(scheduler, "em", em);
        setField(scheduler, "opexDistributionRefreshService", signals);

        invokeNoArg(scheduler, "schedulePracticeCostBasisRefresh");
        invokeNoArg(scheduler, "startPracticeRevenueIfEligible");

        InOrder order = inOrder(jobs, signals);
        order.verify(jobs).start(eq("practice-cost-basis-refresh"), any(Properties.class));
        order.verify(signals).emitReadyCostGenerationSignal();
        order.verify(jobs).start(eq("practice-revenue-refresh"), any(Properties.class));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sql.capture());
        String costSql = sql.getAllValues().get(0);
        String revenueSql = sql.getAllValues().get(1);
        assertTrue(costSql.contains("r.status='PENDING'"));
        assertTrue(costSql.contains("r.request_id=p.latest_cost_basis_request_id"));
        assertTrue(costSql.contains("r.expected_incremental_refresh_version=b.incremental_refresh_version"));
        assertTrue(revenueSql.contains("cr.status IN ('READY','NO_CHANGE')"));
        assertTrue(revenueSql.contains("newer.status IN ('PENDING','RUNNING','FAILED')"));
        assertTrue(revenueSql.contains("newer.status='SUPERSEDED'"));
        assertTrue(revenueSql.contains("successor.status IN ('READY','NO_CHANGE')"));
        assertTrue(revenueSql.contains("p.paired_cost_generation_at <> sig.cost_generation_at"));
    }

    private static EntityManager coherentEntityManager(Object[] requestRow) {
        EntityManager em = mock(EntityManager.class);
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Query query = query();
            if (CxoPracticeContributionService.SNAPSHOT_SQL.equals(sql)) {
                when(query.getResultList()).thenReturn(Collections.singletonList(snapshotRow()));
            } else if (CxoPracticeContributionService.WATERMARK_SQL.equals(sql)) {
                when(query.getResultList()).thenReturn(watermarkRows());
            } else if (CxoPracticeContributionService.LATEST_REQUEST_SQL.equals(sql)) {
                when(query.getResultList()).thenReturn(Collections.singletonList(requestRow));
            } else if (sql.contains("GROUP BY a.segment_id, a.attribution_status")) {
                when(query.getResultList()).thenReturn(List.of());
            } else if (sql.contains("COUNT(DISTINCT source_document_uuid)")) {
                when(query.getResultList()).thenReturn(Collections.singletonList(
                        new Object[]{0L, 0L, 0L, 0L, 0L, 0, 0, 0L, 0L}));
            } else if (sql.contains("controlled_documents")) {
                when(query.getSingleResult()).thenReturn(new Object[]{0, 0, 0L});
            } else if (sql.contains("GROUP_CONCAT(DISTINCT CASE")) {
                when(query.getResultList()).thenReturn(List.of());
            } else {
                throw new AssertionError("Unexpected contribution query: " + sql);
            }
            return query;
        });
        return em;
    }

    private static Query query() {
        Query query = mock(Query.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        return query;
    }

    private static Object[] snapshotRow() {
        Object[] row = new Object[43];
        row[0] = true;
        row[1] = BigInteger.ONE;
        row[2] = "READY";
        row[3] = "revenue-generation-stable";
        row[4] = COST_GENERATION;
        row[5] = BASIS;
        row[6] = BigInteger.valueOf(7);
        for (int i = 7; i <= 15; i++) row[i] = SOURCE_VERSION;
        row[16] = true;
        row[18] = LocalDate.of(2026, 6, 1);
        row[19] = LocalDate.of(2025, 7, 1);
        row[20] = LocalDate.of(2026, 6, 1);
        row[21] = LocalDate.of(2024, 7, 1);
        row[22] = LocalDate.of(2025, 6, 1);
        row[23] = true;
        row[25] = row[18];
        row[26] = row[19];
        row[27] = row[20];
        row[28] = row[21];
        row[29] = row[22];
        row[30] = Instant.parse("2026-07-15T09:00:00Z");
        row[31] = Instant.parse("2026-07-15T08:30:00Z");
        row[32] = BigInteger.ONE;
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

    private static List<Object[]> watermarkRows() {
        List<Object[]> rows = new ArrayList<>();
        for (String name : List.of(
                "INVOICE_DOCUMENT", "FINANCE_GL", "CURRENCY", "ACCOUNT_CLASSIFICATION",
                "INVOICE_ATTRIBUTION", "SELF_BILLED", "PHANTOM_ATTRIBUTION",
                "DELIVERY_EVIDENCE", "PRACTICE_BASIS_INPUT")) {
            rows.add(new Object[]{name, SOURCE_VERSION, "READY"});
        }
        return rows;
    }

    private static Object[] terminalRequest(String status) {
        if ("READY".equals(status)) {
            return new Object[]{BigInteger.ONE, status, REQUEST_KEY, VECTOR,
                    COST_GENERATION, null, BASIS, null, null};
        }
        return request("NO_CHANGE", null);
    }

    private static Object[] request(String status, BigInteger successorRequestId) {
        return new Object[]{BigInteger.ONE, status, REQUEST_KEY, VECTOR,
                null, COST_GENERATION, null, BASIS, successorRequestId};
    }

    private static PracticeOperatingCostResponseDTO completeCost() {
        List<PracticeOperatingCostDTO> practices = List.of(
                zeroCost("PM"), zeroCost("BA"), zeroCost("CYB"), zeroCost("DEV"), zeroCost("SA"));
        return new PracticeOperatingCostResponseDTO(
                "BOOKED", "202606", "202507", "202606", "202407", "202506",
                COST_GENERATION, 12, 12, 12, 12, 12, 12,
                60, 60, 60, 0, 0, 60, 60, 60, 0, 0,
                60, 60, 0, 60, 60, 0,
                true, true, "COMPLETE", true, true, "COMPLETE",
                "COMPLETE", true, "EFFECTIVE_DATED_PRACTICE", LocalDate.of(2020, 1, 1),
                null, practices);
    }

    private static PracticeOperatingCostDTO zeroCost(String practiceId) {
        return new PracticeOperatingCostDTO(practiceId, 0d, 0d, 0d, 0d, 0d, 0d,
                0d, null, 0d, 0d, null, null, null, null);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
