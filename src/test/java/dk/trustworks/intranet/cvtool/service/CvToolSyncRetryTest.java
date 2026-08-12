package dk.trustworks.intranet.cvtool.service;

import dk.trustworks.intranet.cvtool.client.CvToolClient;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeResponse;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeSkinny;
import dk.trustworks.intranet.cvtool.service.CvToolSyncService.CvToolSyncException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly CV sync failed 2 of 7 nights (2026-08-07, 2026-08-11), both times after
 * 30.2s — the client's configured {@code read-timeout} to the CV Tool APIM gateway,
 * hit on the very first call. Because that first call is the employee-list fetch that
 * gates everything else, and because it had no retry, one stalled socket cost the
 * whole night's sync for all ~130 employees.
 *
 * <p>These tests pin the two properties that fixes it: a transient transport failure
 * is retried rather than fatal, and a failure that survives the retries names its real
 * cause in the message instead of burying it two "Caused by:" levels down.
 *
 * <p>Plain JUnit — a @QuarkusTest that aborts at boot is reported as a green SKIP,
 * which would make these assertions vacuous.
 */
class CvToolSyncRetryTest {

    /** Never sleeps: retry policy is exercised without real wall-clock time. */
    private static CvToolSyncService service(CvToolClient client) {
        CvToolSyncService service = new CvToolSyncService();
        service.subscriptionKey = Optional.of("key");
        service.cvToolClient = client;
        service.sleeper = millis -> { };
        return service;
    }

    private static CvToolEmployeeSkinny employee(int id) {
        return new CvToolEmployeeSkinny(id, "Employee " + id, false, "uuid-" + id);
    }

    /**
     * An employee CV Tool holds no CV for. Used as the "call succeeded" payload because
     * it returns SKIPPED before any database access, keeping these tests container-free.
     */
    private static CvToolEmployeeResponse employeeWithoutCv(int id) {
        return new CvToolEmployeeResponse(id, "Employee " + id, null, null,
                "uuid-" + id, false, null, null, null);
    }

    /** Exactly what RESTEasy raises when the read-timeout fires: no response ever arrived. */
    private static ProcessingException readTimeout() {
        return new ProcessingException(new SocketTimeoutException("Read timed out"));
    }

    // ---------------------------------------------------------------- list fetch

    /**
     * The headline regression: the production failure, survived. One timed-out list
     * fetch must not cost the night — the second attempt hits a warm gateway and the
     * run completes.
     */
    @Test
    void transientTimeoutOnEmployeeListIsRetriedAndTheRunSucceeds() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenThrow(readTimeout()).thenReturn(List.of());

        String result = service(client).syncAllBaseCvs();

        assertTrue(result.startsWith("COMPLETED:"), "one transient timeout must not fail the run, was: " + result);
        verify(client, times(2)).getAllEmployees();
    }

    /** A blip on the last allowed attempt still succeeds — the ladder is used, not just probed. */
    @Test
    void employeeListIsRetriedUpToTheAttemptLimit() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees())
                .thenThrow(readTimeout())
                .thenThrow(readTimeout())
                .thenReturn(List.of());

        assertTrue(service(client).syncAllBaseCvs().startsWith("COMPLETED:"));
        verify(client, times(CvToolSyncService.MAX_ATTEMPTS_PER_CALL)).getAllEmployees();
    }

    /**
     * When retries genuinely cannot save it, the failure must be diagnosable from the
     * message alone. This is the other half of the incident: the log showed only
     * "Could not fetch employee list from CV Tool", so nobody could tell a socket
     * timeout from a 502 without digging through the stack trace.
     */
    @Test
    void exhaustedRetriesFailWithTheRootCauseInTheMessage() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenThrow(readTimeout());

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(client).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("SocketTimeoutException"),
                "the message must name the real cause, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Read timed out"),
                "the message must carry the cause's text, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("3 attempt"),
                "the message must say how many attempts were made, was: " + ex.getMessage());
        verify(client, times(CvToolSyncService.MAX_ATTEMPTS_PER_CALL)).getAllEmployees();
    }

    /**
     * A bad subscription key does not heal on attempt two. Retrying it would only turn
     * a clear auth failure into a slow one — and this sync has already lost a month to
     * an auth failure nobody could see.
     */
    @Test
    void authFailureIsNotRetried() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenThrow(new WebApplicationException(401));

        assertThrows(CvToolSyncException.class, () -> service(client).syncAllBaseCvs());

        verify(client, times(1)).getAllEmployees();
    }

    /**
     * The message must report the attempts actually made, not the configured maximum.
     * A 401 stops on the first attempt by design, so claiming "3 attempt(s)" would
     * assert a retry ladder that never ran — and would contradict the elapsed time
     * printed beside it, since three attempts cannot fit inside their own 6s of
     * backoff. Being the line on-call reads first, it has to be true.
     */
    @Test
    void aFailureThatIsNotRetriedReportsOneAttempt() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenThrow(new WebApplicationException(401));

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(client).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("after 1 attempt(s)"),
                "a non-retried failure must not claim the full ladder ran, was: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("after 3 attempt(s)"), ex.getMessage());
    }

    /** The count tracks the real ladder when a transient failure does exhaust it. */
    @Test
    void anExhaustedLadderReportsEveryAttempt() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenThrow(readTimeout());

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(client).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("after 3 attempt(s)"), ex.getMessage());
    }

    /** A gateway that is briefly unavailable is exactly what retrying is for. */
    @Test
    void gatewayUnavailableIsRetried() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees())
                .thenThrow(new WebApplicationException(503))
                .thenReturn(List.of());

        assertTrue(service(client).syncAllBaseCvs().startsWith("COMPLETED:"));
        verify(client, times(2)).getAllEmployees();
    }

    // ------------------------------------------------------------ per-employee fetch

    /**
     * Any single employee failure fails the whole run, so a stalled socket on 1 of ~130
     * turns the night red even though the other 129 CVs were written. Retry it on the
     * same terms as the list fetch.
     */
    @Test
    void transientTimeoutOnASingleEmployeeIsRetriedAndTheRunStaysGreen() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of(employee(1)));
        when(client.getEmployee(1)).thenThrow(readTimeout()).thenReturn(employeeWithoutCv(1));

        String result = service(client).syncAllBaseCvs();

        assertTrue(result.startsWith("COMPLETED:"), "was: " + result);
        assertTrue(result.contains("0 failed"), "the retry must clear the failure, was: " + result);
        verify(client, times(2)).getEmployee(1);
    }

    /**
     * The safety valve. If the gateway dies mid-run, each remaining employee must not
     * burn its own full retry ladder — that would stretch a doomed night into hours of
     * pointless traffic against a dead upstream.
     *
     * <p>With a budget of 8 and 3 attempts per call, the first four employees spend
     * 2 retries each (3 calls apiece) and exhaust it; the remaining two get a single
     * attempt with no retry. 4x3 + 2x1 = 14 calls, not 6x3 = 18.
     */
    @Test
    void runWideBudgetStopsAnOutageFromRetryingEveryEmployee() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of(
                employee(1), employee(2), employee(3), employee(4), employee(5), employee(6)));
        when(client.getEmployee(anyInt())).thenThrow(readTimeout());

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(client).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("6 failed"), "was: " + ex.getMessage());
        int budgetedCalls = 6 + CvToolSyncService.RETRY_BUDGET_PER_RUN; // one attempt each, plus the budget
        verify(client, times(budgetedCalls)).getEmployee(anyInt());
    }

    // ------------------------------------------------------------------ policy units

    @Test
    void onlyTransportAndGatewayFailuresAreRetryable() {
        assertTrue(CvToolRetryExecutor.isTransient(readTimeout()), "read timeout");
        assertTrue(CvToolRetryExecutor.isTransient(new ProcessingException(new IOException("connection reset"))));
        assertTrue(CvToolRetryExecutor.isTransient(new WebApplicationException(502)), "bad gateway");
        assertTrue(CvToolRetryExecutor.isTransient(new WebApplicationException(504)), "gateway timeout");
        assertTrue(CvToolRetryExecutor.isTransient(new WebApplicationException(429)), "throttled");

        assertFalse(CvToolRetryExecutor.isTransient(new WebApplicationException(401)), "auth must fail fast");
        assertFalse(CvToolRetryExecutor.isTransient(new WebApplicationException(404)), "not found is not transient");
        assertFalse(CvToolRetryExecutor.isTransient(new WebApplicationException(500)),
                "an upstream application error is usually deterministic");
        assertFalse(CvToolRetryExecutor.isTransient(new IllegalStateException("bug")));
    }

    @Test
    void backoffGrowsAndIsCapped() {
        assertEquals(2000L, CvToolRetryExecutor.backoffMillis(1));
        assertEquals(4000L, CvToolRetryExecutor.backoffMillis(2));
        assertEquals(8000L, CvToolRetryExecutor.backoffMillis(3));
        assertEquals(CvToolRetryExecutor.backoffMillis(50), CvToolRetryExecutor.backoffMillis(100),
                "a pathological attempt count must not overflow the shift");
    }

    @Test
    void rootCauseReachesTheDeepestCause() {
        Throwable deep = new ProcessingException(new SocketTimeoutException("Read timed out"));
        assertEquals("SocketTimeoutException: Read timed out", CvToolRetryExecutor.rootCause(deep));
    }

    @Test
    void rootCauseHandlesNullAndMessagelessAndCyclicChains() {
        assertEquals("unknown cause", CvToolRetryExecutor.rootCause(null));
        assertEquals("IllegalStateException", CvToolRetryExecutor.rootCause(new IllegalStateException()));

        // A self-referencing cause must terminate rather than spin.
        Throwable selfReferencing = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertTrue(CvToolRetryExecutor.rootCause(selfReferencing).contains("loop"));
    }
}
