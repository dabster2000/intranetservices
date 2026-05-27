package dk.trustworks.intranet.aggregates.finance.health;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit tests for {@link EconomicRevenueImportFreshnessCheck}.
 *
 * <p>Covers the 5 edge cases called out in PR 2 plan §"Acceptance criteria (2b)" #7:
 * empty table, fresh, stale, LocalDateTime (instead of Timestamp) value, and
 * null timestamp with non-zero row count.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EconomicRevenueImportFreshnessCheckTest {

    @Mock EntityManagerFactory emf;
    @Mock EntityManager em;
    @Mock Query query;

    EconomicRevenueImportFreshnessCheck check;

    @BeforeEach
    void setUp() {
        check = new EconomicRevenueImportFreshnessCheck();
        check.emf = emf;
        check.maxStalenessHours = 25;
        when(emf.createEntityManager()).thenReturn(em);
    }

    /**
     * Cold-start: zero auto-imported rows yet. Cold-start is NOT stale —
     * the cold-start guard in {@link EconomicRevenueImportBatchlet#onStart}
     * will trigger an async refresh independently, so readiness must stay UP
     * so the task can serve traffic.
     */
    @Test
    void testEmptyTableReturnsUp() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new Object[]{null, 0L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.UP, result.getStatus());
        assertTrue(result.getData().isPresent(),
                "expected health-check data to be present");
        assertEquals(0L, result.getData().get().get("rows"));
        assertEquals("never", result.getData().get().get("max_refreshed_at"));
    }

    @Test
    void testFreshReturnsUp() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(
                new Object[]{Timestamp.valueOf(oneHourAgo), 100L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.UP, result.getStatus());
        assertEquals(100L, result.getData().get().get("rows"));
    }

    @Test
    void testStaleReturnsDown() {
        LocalDateTime thirtyHoursAgo = LocalDateTime.now().minusHours(30);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(
                new Object[]{Timestamp.valueOf(thirtyHoursAgo), 100L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, result.getStatus());
        assertEquals(100L, result.getData().get().get("rows"));
    }

    /**
     * Reproduces the bug fixed in the opex check: some JDBC paths return
     * {@link LocalDateTime} directly instead of {@link Timestamp}. The
     * instanceof-pattern in the production code must handle both without
     * a ClassCastException.
     */
    @Test
    void testLocalDateTimeHandling() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(
                new Object[]{oneHourAgo, 100L});

        // Must not throw ClassCastException.
        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.UP, result.getStatus());
    }

    /**
     * Defensive: if the DB happens to return MAX(...) = NULL even with rowCount > 0
     * (transient state during cold-start writes), we must not NPE and we must
     * return UP — same cold-start semantics as {@link #testEmptyTableReturnsUp}.
     */
    @Test
    void testNullLocalDateTimeHandling() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new Object[]{null, 100L});

        // Must not throw NullPointerException.
        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.UP, result.getStatus(),
                "null max-timestamp with rowCount>0 = cold-start in progress; treat as UP");
        assertEquals(100L, result.getData().get().get("rows"));
        assertEquals("never", result.getData().get().get("max_refreshed_at"));
    }
}
