package dk.trustworks.intranet.aggregates.finance.jobs;

import dk.trustworks.intranet.aggregates.finance.dto.DryRunOutcome;
import dk.trustworks.intranet.aggregates.finance.services.EconomicRevenueImportService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit tests for {@link EconomicRevenueImportBatchlet}.
 *
 * <p>Mirrors {@code OpexDistributionRefreshBatchletTest}: wires the
 * package-private fields directly, so no Quarkus context is needed.
 *
 * <p>Critical assertions specific to PR 2:
 * <ul>
 *   <li>Slack alerts route to channel {@code C0B2VQ2CFU1} via the
 *       {@code mother} bot token (verified working on 2026-05-13).</li>
 *   <li>6h rate-limit suppresses duplicate Slack alerts.</li>
 *   <li>Cold-start guard fires when {@code invoices} contains zero rows
 *       with {@code economics_entry_number IS NOT NULL}.</li>
 *   <li>Slack message sanitizer strips token/secret-like substrings
 *       (≥20 alnum chars after {@code Token=} / {@code Secret=}) and
 *       caps at 200 chars before delivery.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EconomicRevenueImportBatchletTest {

    private static final String CHANNEL = "C0B2VQ2CFU1";
    private static final String TOKEN_KEY = "mother";

    @Mock EconomicRevenueImportService refreshService;
    @Mock SlackService slackService;
    @Mock ManagedExecutor managedExecutor;
    @Mock EntityManager em;
    @Mock Query query;

    EconomicRevenueImportBatchlet batchlet;

    @BeforeEach
    void setUp() {
        batchlet = new EconomicRevenueImportBatchlet();
        batchlet.refreshService = refreshService;
        batchlet.slackService = slackService;
        batchlet.managedExecutor = managedExecutor;
        batchlet.em = em;
        batchlet.opsAlertChannel = CHANNEL;
    }

    private DryRunOutcome emptyOutcome() {
        return new DryRunOutcome(
                0, 0,
                Map.of(),
                Map.of(),
                Map.of(),
                true);
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void scheduledRun_success_logsAndClearsAlertState() {
        batchlet.lastAlertSent.set(Instant.now());
        when(refreshService.refresh(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new DryRunOutcome(
                        7, 0,
                        Map.of("A/S", BigDecimal.valueOf(18_500_000L)),
                        Map.of(2104, BigDecimal.valueOf(18_500_000L)),
                        Map.of(),
                        true));

        batchlet.scheduledRun();

        assertNull(batchlet.lastAlertSent.get(),
                "successful run must clear the rate-limit timestamp");
        verify(slackService, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void scheduledRun_invokesServiceWith24MonthLookback() {
        when(refreshService.refresh(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(emptyOutcome());

        batchlet.scheduledRun();

        ArgumentCaptor<LocalDate> fromCap = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCap = ArgumentCaptor.forClass(LocalDate.class);
        verify(refreshService).refresh(fromCap.capture(), toCap.capture());
        LocalDate today = LocalDate.now();
        assertEquals(today, toCap.getValue(), "to-date must be today");
        assertEquals(today.minusMonths(24), fromCap.getValue(),
                "from-date must be 24 months back");
    }

    // ------------------------------------------------------------------
    // Slack alerting — channel + token
    // ------------------------------------------------------------------

    @Test
    void testScheduledRunFailureFiresSlackAlert() {
        when(refreshService.refresh(any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new RuntimeException("boom"));

        batchlet.scheduledRun();

        verify(slackService, times(1)).sendMessage(
                eq(CHANNEL), anyString(), eq(TOKEN_KEY));
        assertNotNull(batchlet.lastAlertSent.get(),
                "first failure must set the rate-limit timestamp");
    }

    // ------------------------------------------------------------------
    // Rate-limiting
    // ------------------------------------------------------------------

    @Test
    void testSecondFailureWithinWindowSuppressed() {
        batchlet.lastAlertSent.set(Instant.now().minus(Duration.ofMinutes(5)));
        when(refreshService.refresh(any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new RuntimeException("boom"));

        batchlet.scheduledRun();

        verify(slackService, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void scheduledRun_failureAfterRateLimitWindow_postsSlackAgain() {
        batchlet.lastAlertSent.set(Instant.now().minus(Duration.ofHours(7)));
        when(refreshService.refresh(any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new RuntimeException("boom"));

        batchlet.scheduledRun();

        verify(slackService, times(1)).sendMessage(
                eq(CHANNEL), anyString(), eq(TOKEN_KEY));
    }

    // ------------------------------------------------------------------
    // Cold-start guard
    // ------------------------------------------------------------------

    @Test
    void testColdStartTriggersOneShotRefresh() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);

        batchlet.onStart(null);

        verify(managedExecutor, times(1)).submit(any(Runnable.class));
        // The Runnable is queued — the synchronous service call must NOT happen
        // inside onStart itself (would block the StartupEvent thread).
        verify(refreshService, never()).refresh(any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void onStart_populatedTable_doesNotTriggerRefresh() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(123L);

        batchlet.onStart(null);

        verify(managedExecutor, never()).submit(any(Runnable.class));
    }

    // ------------------------------------------------------------------
    // Token / secret sanitization
    // ------------------------------------------------------------------

    @Test
    void testSlackAlertSanitizesTokens() {
        when(refreshService.refresh(any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new RuntimeException(
                        "401 Unauthorized — X-AgreementGrantToken=abc123abc123abc123abc123XYZ"));

        batchlet.scheduledRun();

        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).sendMessage(
                eq(CHANNEL), msgCap.capture(), eq(TOKEN_KEY));

        String delivered = msgCap.getValue();
        assertFalse(delivered.contains("abc123abc123abc123abc123XYZ"),
                "token tail must be redacted before delivery");
        assertTrue(delivered.contains("Token=***REDACTED***"),
                "Token= prefix must be preserved with REDACTED placeholder");
    }

    @Test
    void sanitizeSlackMessage_capsAt200Chars() {
        String huge = "x".repeat(500);

        String result = EconomicRevenueImportBatchlet.sanitizeSlackMessage(huge);

        assertTrue(result.length() <= EconomicRevenueImportBatchlet.SLACK_MESSAGE_MAX_CHARS,
                "sanitizer must enforce 200-char cap");
        assertTrue(result.endsWith("…"),
                "truncated messages must indicate overflow");
    }

    @Test
    void sanitizeSlackMessage_stripsSecretAssignments() {
        String input = "auth failed; X-AppSecretToken=" + "A".repeat(40) + " end";

        String result = EconomicRevenueImportBatchlet.sanitizeSlackMessage(input);

        assertFalse(result.contains("A".repeat(40)),
                "long alnum runs after Token= must be redacted");
    }

    @Test
    void sanitizeSlackMessage_nullInput_returnsEmpty() {
        assertEquals("", EconomicRevenueImportBatchlet.sanitizeSlackMessage(null));
    }

    // ------------------------------------------------------------------
    // Cron contract
    // ------------------------------------------------------------------

    @Test
    void scheduledRun_isAnnotatedWithNightly0200Cron() throws NoSuchMethodException {
        Method m = EconomicRevenueImportBatchlet.class.getMethod("scheduledRun");
        io.quarkus.scheduler.Scheduled annotation =
                m.getAnnotation(io.quarkus.scheduler.Scheduled.class);
        assertNotNull(annotation, "scheduledRun must carry @Scheduled");
        assertEquals("0 0 2 * * ?", annotation.cron(),
                "e-conomic import runs at 02:00 UTC, 90 min before opex at 03:30 UTC.");
        assertEquals("economic-revenue-import", annotation.identity(),
                "@Scheduled identity must match the documented job name.");
    }
}
