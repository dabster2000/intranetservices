package dk.trustworks.intranet.aggregates.finance.jobs;

import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService;
import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService.RefreshOutcome;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit tests for {@link OpexDistributionRefreshBatchlet}.
 * Mirrors {@code FactChangeLogBacklogAlertBatchletTest}: wires the
 * package-private fields directly, so no Quarkus context is needed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpexDistributionRefreshBatchletTest {

    private static final String CHANNEL = "C0B2VQ2CFU1";

    @Mock OpexDistributionRefreshService refreshService;
    @Mock SlackService slackService;
    @Mock ManagedExecutor managedExecutor;
    @Mock EntityManager em;
    @Mock Query query;

    OpexDistributionRefreshBatchlet batchlet;

    @BeforeEach
    void setUp() {
        batchlet = new OpexDistributionRefreshBatchlet();
        batchlet.refreshService = refreshService;
        batchlet.slackService = slackService;
        batchlet.managedExecutor = managedExecutor;
        batchlet.em = em;
        batchlet.opsAlertChannel = CHANNEL;
    }

    @Test
    void scheduledRun_success_logsAndClearsAlertState() {
        batchlet.lastAlertSent.set(Instant.now());
        when(refreshService.refresh()).thenReturn(
                new RefreshOutcome(42, 42, Duration.ofMillis(123),
                        LocalDate.of(2025, 7, 1), LocalDate.of(2026, 7, 1)));

        batchlet.scheduledRun();

        assertNull(batchlet.lastAlertSent.get());
        verify(slackService, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void scheduledRun_firstFailure_postsSlackAndSetsAlertTimestamp() {
        when(refreshService.refresh()).thenThrow(new RuntimeException("boom"));

        batchlet.scheduledRun();

        verify(slackService, times(1)).sendMessage(
                eq(CHANNEL), anyString(), eq("mother"));
        assertNotNull(batchlet.lastAlertSent.get());
    }

    @Test
    void scheduledRun_secondFailureWithinWindow_suppressesDuplicateSlack() {
        batchlet.lastAlertSent.set(Instant.now().minus(Duration.ofMinutes(5)));
        when(refreshService.refresh()).thenThrow(new RuntimeException("boom"));

        batchlet.scheduledRun();

        verify(slackService, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void scheduledRun_failureAfterRateLimitWindow_postsSlackAgain() {
        batchlet.lastAlertSent.set(Instant.now().minus(Duration.ofHours(7)));
        when(refreshService.refresh()).thenThrow(new RuntimeException("boom"));

        batchlet.scheduledRun();

        verify(slackService, times(1)).sendMessage(
                eq(CHANNEL), anyString(), eq("mother"));
    }

    @Test
    void onStart_emptyTable_submitsAsyncRefresh() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);

        batchlet.onStart(null);

        verify(managedExecutor, times(1)).submit(org.mockito.ArgumentMatchers.<Runnable>any());
        verify(refreshService, never()).refresh();
    }

    @Test
    void onStart_populatedTable_doesNotTriggerRefresh() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(123L);

        batchlet.onStart(null);

        verify(managedExecutor, never()).submit(org.mockito.ArgumentMatchers.<Runnable>any());
    }

    @Test
    void scheduledRun_isAnnotatedWithNightlyCron() throws NoSuchMethodException {
        Method m = OpexDistributionRefreshBatchlet.class.getMethod("scheduledRun");
        io.quarkus.scheduler.Scheduled annotation =
                m.getAnnotation(io.quarkus.scheduler.Scheduled.class);
        assertNotNull(annotation, "scheduledRun must carry @Scheduled");
        assertEquals("0 30 3 * * ?", annotation.cron(),
                "Distribution refresh runs at 03:30 UTC nightly.");
    }
}
