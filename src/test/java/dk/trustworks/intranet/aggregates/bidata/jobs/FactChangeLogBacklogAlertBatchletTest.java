package dk.trustworks.intranet.aggregates.bidata.jobs;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit tests. The single backlog query returns {@code Object[]}
 * with {pending, oldestMinutes}; we stub it via {@link #stubBacklog}.
 */
@ExtendWith(MockitoExtension.class)
class FactChangeLogBacklogAlertBatchletTest {

    private static final String CHANNEL = "C0B2VQ2CFU1";

    @InjectMocks
    FactChangeLogBacklogAlertBatchlet batchlet;

    @Mock
    EntityManager em;

    @Mock
    SlackService slackService;

    @Mock
    Query backlogQuery;

    @BeforeEach
    void setUp() {
        batchlet.opsAlertChannel = CHANNEL;
        batchlet.maxPending = 500L;
        batchlet.maxOldestPendingMinutes = 30L;
    }

    @Test
    void pendingExceedsThreshold_sendsSlackAlert() {
        stubBacklog(600L, 5L);

        batchlet.checkBacklog();

        verify(slackService).sendMessage(
                eq(CHANNEL),
                contains("pending events: 600"),
                eq("mother"));
    }

    @Test
    void healthyBacklog_doesNotSendAlert() {
        stubBacklog(10L, null);

        batchlet.checkBacklog();

        verify(slackService, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void oldestPendingExceedsThreshold_sendsSlackAlert() {
        stubBacklog(5L, 45L);

        batchlet.checkBacklog();

        verify(slackService).sendMessage(
                eq(CHANNEL),
                contains("oldest pending: 45 minutes"),
                eq("mother"));
    }

    @Test
    void repeatBreachWithinWindow_doesNotResendAlert() {
        stubBacklog(600L, 5L);

        batchlet.checkBacklog();
        batchlet.checkBacklog();

        verify(slackService, times(1)).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void breachClearsThenRecurs_alertFiresAgainImmediately() {
        stubBacklog(600L, 5L);
        batchlet.checkBacklog();

        stubBacklog(10L, null);
        batchlet.checkBacklog();

        stubBacklog(700L, 5L);
        batchlet.checkBacklog();

        // Two alerts: first breach + re-occurrence after recovery cleared the window.
        verify(slackService, times(2)).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void alertMessage_includesAllRequiredFields() {
        stubBacklog(750L, 42L);

        batchlet.checkBacklog();

        verify(slackService).sendMessage(eq(CHANNEL),
                argThat((String msg) -> msg.contains(":rotating_light:")
                        && msg.contains("fact_change_log backlog alert")
                        && msg.contains("pending events: 750")
                        && msg.contains("oldest pending: 42 minutes")
                        && msg.contains("ev_bi_incremental_refresh")),
                eq("mother"));
    }

    @Test
    void scheduledRun_swallowsExceptions() {
        when(em.createNativeQuery(anyString()))
                .thenThrow(new RuntimeException("simulated DB outage"));

        batchlet.scheduledRun();

        verify(slackService, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void scheduledRun_runsEveryFiveMinutes() throws NoSuchMethodException {
        Method m = FactChangeLogBacklogAlertBatchlet.class.getMethod("scheduledRun");
        io.quarkus.scheduler.Scheduled annotation =
                m.getAnnotation(io.quarkus.scheduler.Scheduled.class);
        assertNotNull(annotation, "scheduledRun must carry @Scheduled");
        assertEquals("0 */5 * * * ?", annotation.cron(),
                "Backlog alert should run every 5 minutes (aligned with ev_bi_incremental_refresh).");
    }

    private void stubBacklog(long pending, Long oldestMinutes) {
        when(em.createNativeQuery(anyString())).thenReturn(backlogQuery);
        when(backlogQuery.getSingleResult())
                .thenReturn(new Object[]{Long.valueOf(pending), oldestMinutes});
    }
}
