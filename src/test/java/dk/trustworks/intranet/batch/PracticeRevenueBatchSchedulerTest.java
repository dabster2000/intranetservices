package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService;
import jakarta.batch.operations.JobOperator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PracticeRevenueBatchSchedulerTest {
    @Mock JobOperator jobOperator;
    @Mock EntityManager em;
    @Mock Query query;
    @Mock OpexDistributionRefreshService opexDistributionRefreshService;

    BatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchScheduler();
        scheduler.jobOperator = jobOperator;
        scheduler.em = em;
        scheduler.opexDistributionRefreshService = opexDistributionRefreshService;
        when(em.createNativeQuery(anyString())).thenReturn(query);
        org.mockito.Mockito.lenient().when(jobOperator.getJobNames()).thenReturn(Collections.emptySet());
    }

    @Test
    void costPollStartsOnlyExactLatestRequestAfterReadyFullBi() {
        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[]{
                BigInteger.valueOf(17), "a".repeat(64), "b".repeat(64)}));

        scheduler.schedulePracticeCostBasisRefresh();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("b.refresh_state='READY'"));
        assertTrue(sql.getValue().contains("b.active_refresh_token IS NULL"));
        assertTrue(sql.getValue().contains("r.request_id=p.latest_cost_basis_request_id"));
        assertTrue(sql.getValue().contains("r.expected_full_refresh_version=b.full_refresh_version"));
        ArgumentCaptor<Properties> parameters = ArgumentCaptor.forClass(Properties.class);
        verify(jobOperator).start(anyString(), parameters.capture());
        assertTrue(parameters.getValue().stringPropertyNames().containsAll(java.util.Set.of(
                "expectedRequestId", "expectedRequestKey", "expectedInputVectorFingerprint")));
        assertTrue("17".equals(parameters.getValue().getProperty("expectedRequestId")));
    }

    @Test
    void neverRunRevenueJobStartsWithoutCallingRunningExecutions() {
        when(query.getSingleResult()).thenReturn(1L);
        when(jobOperator.getJobNames()).thenReturn(Collections.emptySet());

        scheduler.startPracticeRevenueIfEligible();

        verify(jobOperator, never()).getRunningExecutions("practice-revenue-refresh");
        verify(jobOperator).start(anyString(), any(Properties.class));
    }

    @Test
    void newReadyCostSignalBypassesSourceQuietDelayAndStartsRevenueAfterCostCertification() {
        when(query.getSingleResult()).thenReturn(1L);

        scheduler.startPracticeRevenueIfEligible();

        verify(opexDistributionRefreshService).emitReadyCostGenerationSignal();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("practice_cost_generation_signal"));
        assertTrue(sql.getValue().contains("p.paired_cost_generation_at <> sig.cost_generation_at"));
        assertTrue(sql.getValue().contains("newer.request_id > o.certified_cost_basis_request_id"));
        assertTrue(sql.getValue().contains("newer.status='SUPERSEDED'"));
        assertTrue(sql.getValue().contains("successor.status IN ('READY','NO_CHANGE')"));
        assertTrue(sql.getValue().contains("DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE)"));
        verify(jobOperator).start(anyString(), any(Properties.class));
    }

    @Test
    void noCertifiedCostOrQuietRevenueChangeDoesNotStartRevenue() {
        when(query.getSingleResult()).thenReturn(0L);

        scheduler.startPracticeRevenueIfEligible();

        verify(jobOperator, never()).start(anyString(), any(Properties.class));
    }
}
