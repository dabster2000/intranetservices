package dk.trustworks.intranet.aggregates.finance.health;

import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.FiscalYearRange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpexDistributionFreshnessCheckTest {

    @Mock EntityManagerFactory emf;
    @Mock EntityManager em;
    @Mock Query query;

    OpexDistributionFreshnessCheck check;

    @BeforeEach
    void setUp() {
        check = new OpexDistributionFreshnessCheck();
        check.emf = emf;
        check.maxStalenessHours = 30;
        check.refreshWindowFyBack = 1;
        when(emf.createEntityManager()).thenReturn(em);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        // The freshness query binds :fromKey/:toKey — return the same mock for chaining.
        when(query.setParameter(anyString(), any())).thenReturn(query);
    }

    /**
     * Cold-start: empty window. Production code treats this as UP (not stale,
     * just not yet populated) per its own javadoc — the nightly refresh job
     * will populate the table independently.
     */
    @Test
    void emptyTable_returnsUp() {
        when(query.getSingleResult()).thenReturn(new Object[]{null, 0L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.UP, result.getStatus());
        assertTrue(result.getData().isPresent(),
                "expected health-check data to be present");
        assertEquals(0L, result.getData().get().get("rows"));
    }

    @Test
    void freshRows_returnsUp() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        when(query.getSingleResult()).thenReturn(
                new Object[]{Timestamp.valueOf(oneHourAgo), 400L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.UP, result.getStatus());
    }

    @Test
    void staleRows_returnsDown() {
        LocalDateTime longAgo = LocalDateTime.now().minusHours(48);
        when(query.getSingleResult()).thenReturn(
                new Object[]{Timestamp.valueOf(longAgo), 400L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, result.getStatus());
    }

    /**
     * Regression guard for the 2026-07-01 prod outage: the freshness query MUST be
     * scoped to the same month_key window the nightly refresh rewrites, so the
     * never-refreshed historical rows of prior fiscal years cannot trip the check.
     * The window keys must match the refresh service's window computation exactly.
     */
    @Test
    void query_isScopedToRefreshWindow() {
        when(query.getSingleResult()).thenReturn(
                new Object[]{Timestamp.valueOf(LocalDateTime.now().minusHours(1)), 285L});

        check.call();

        // Same computation as OpexDistributionRefreshService.refresh() with fyBack=1.
        FiscalYearRange fy = UtilizationCalculationHelper.getCurrentFiscalYearRange();
        String expectedFromKey = UtilizationCalculationHelper.toMonthKey(fy.start().minusYears(1));
        String expectedToKey = UtilizationCalculationHelper.toMonthKey(fy.end().plusDays(1));

        verify(em).createNativeQuery(contains("month_key >= :fromKey AND month_key < :toKey"));
        verify(query).setParameter("fromKey", expectedFromKey);
        verify(query).setParameter("toKey", expectedToKey);
    }
}
