package dk.trustworks.intranet.cvtool.service;

import dk.trustworks.intranet.cvtool.client.CvToolClient;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeResponse;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeSkinny;
import dk.trustworks.intranet.cvtool.service.CvToolSyncService.CvToolSyncException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * On 2026-08-12 the nightly sync wrote 128 CVs, left 7 unchanged, skipped 11 — and was
 * marked FAILED, because two employees (10 and 11) each lost a single call to a 30s
 * read timeout. {@code failed > 0} made those two indistinguishable from a sync that
 * was completely dead, which is the one distinction the batch monitor exists to make.
 *
 * <p>These tests pin the tolerance that replaced it, from both sides: a realistic
 * production run survives a couple of stalled sockets, and anything above the cap still
 * fails the job exactly as before. The cap is calibrated against two real runs — the
 * 2 of 148 above, which must pass, and the {@code uk_useruuid} incident's 9 of 136,
 * which must not.
 *
 * <p>Plain JUnit — a @QuarkusTest that aborts at boot is reported as a green SKIP,
 * which would make these assertions vacuous.
 */
class CvToolSyncPartialFailureTest {

    /** The production roster size on the night this policy was written. */
    private static final int ROSTER = 148;

    private final List<Handler> attached = new ArrayList<>();

    @AfterEach
    void detachHandlers() {
        Logger logger = Logger.getLogger(CvToolSyncService.class.getName());
        attached.forEach(logger::removeHandler);
        attached.clear();
    }

    /**
     * Wired with the production defaults. {@code @ConfigProperty} does not run in plain
     * JUnit, so an unassigned threshold would silently be 0 — under which every failure
     * breaches the cap and every tolerance assertion here would pass vacuously.
     */
    private static CvToolSyncService service(CvToolClient client) {
        CvToolSyncService service = new CvToolSyncService();
        service.subscriptionKey = Optional.of("key");
        service.cvToolClient = client;
        service.sleeper = millis -> { };
        service.syncFailureThreshold = 3;
        service.syncFailurePercent = 5.0;
        return service;
    }

    private static CvToolEmployeeSkinny employee(int id) {
        return new CvToolEmployeeSkinny(id, "Employee " + id, false, "uuid-" + id);
    }

    /**
     * An employee CV Tool holds no CV for. Used as the "call succeeded" payload because
     * it returns SKIPPED before any database access, keeping these tests container-free.
     * The healthy employees below therefore land in {@code skipped} rather than
     * {@code synced} — which is why the threshold is deliberately expressed against the
     * total roster and never against {@code synced}. A rule phrased in terms of synced
     * CVs would be untestable without a database, and would land in the @QuarkusTest
     * tier that CI does not gate.
     */
    private static CvToolEmployeeResponse employeeWithoutCv(int id) {
        return new CvToolEmployeeResponse(id, "Employee " + id, null, null,
                "uuid-" + id, false, null, null, null);
    }

    /** Exactly what RESTEasy raises when the read-timeout fires: no response ever arrived. */
    private static ProcessingException readTimeout() {
        return new ProcessingException(new SocketTimeoutException("Read timed out"));
    }

    /**
     * A roster of {@code total} healthy employees, with {@code failing} of them throwing.
     * A 500 is used for the bulk failures on purpose: it is deliberately not retryable
     * (CvToolRetryExecutor.RETRYABLE_STATUSES), so each costs exactly one call and the
     * run-wide retry budget stays out of the arithmetic these tests are about.
     */
    private static CvToolClient rosterWithFailures(int total, int failing) {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(
                IntStream.rangeClosed(1, total)
                        .mapToObj(CvToolSyncPartialFailureTest::employee)
                        .toList());
        when(client.getEmployee(anyInt())).thenReturn(employeeWithoutCv(1));
        for (int id = total - failing + 1; id <= total; id++) {
            when(client.getEmployee(id)).thenThrow(new WebApplicationException(500));
        }
        return client;
    }

    private List<String> captureErrors() {
        List<String> errors = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    errors.add(record.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger logger = Logger.getLogger(CvToolSyncService.class.getName());
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        attached.add(handler);
        return errors;
    }

    // ------------------------------------------------------------ below the cap

    /**
     * The headline regression: production 2026-08-12, survived. Two employees lost to
     * transport failures out of a full roster must not turn the night red.
     */
    @Test
    void twoFailuresOutOfAFullRosterDoNotFailTheJob() {
        String result = service(rosterWithFailures(ROSTER, 2)).syncAllBaseCvs();

        assertTrue(result.startsWith("COMPLETED:"),
                "2 of " + ROSTER + " must not fail the run, was: " + result);
        assertTrue(result.contains("2 failed"), result);
        assertTrue(result.contains("(out of " + ROSTER + " total)"), result);
    }

    /**
     * The same two employees, failing the way they actually failed — a stalled socket
     * that survives the retry ladder — rather than through the cheaper 500. Costs 2
     * retries each against a budget of 8, so the ladder and the tolerance coexist.
     */
    @Test
    void twoExhaustedReadTimeoutsDoNotFailTheJob() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(
                IntStream.rangeClosed(1, ROSTER)
                        .mapToObj(CvToolSyncPartialFailureTest::employee).toList());
        when(client.getEmployee(anyInt())).thenReturn(employeeWithoutCv(1));
        when(client.getEmployee(10)).thenThrow(readTimeout());
        when(client.getEmployee(11)).thenThrow(readTimeout());

        String result = service(client).syncAllBaseCvs();

        assertTrue(result.startsWith("COMPLETED:"), "was: " + result);
        assertTrue(result.contains("2 failed"), result);
    }

    /** Exactly at the cap is still tolerated; the cap is the last good value, not the first bad one. */
    @Test
    void failuresExactlyAtTheThresholdAreTolerated() {
        String result = service(rosterWithFailures(ROSTER, 3)).syncAllBaseCvs();

        assertTrue(result.startsWith("COMPLETED:"), "3 is the cap, not one past it, was: " + result);
        assertTrue(result.contains("3 failed"), result);
    }

    /**
     * The tolerated run stays COMPLETED, so nothing downstream will notice it: the
     * metric records outcome="success" and the returned string lands in exit_status,
     * which nothing reads. This ERROR line is the entire alarm — if a refactor drops
     * it, tolerated failures become as silent as the outage this job already had once.
     */
    @Test
    void aToleratedRunReportsTheTokenAndNamesWhoWasLeftBehind() {
        List<String> errors = captureErrors();

        service(rosterWithFailures(ROSTER, 2)).syncAllBaseCvs();

        String token = errors.stream()
                .filter(m -> m.contains("CVTOOL_SYNC_PARTIAL_FAILURE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "a tolerated failure must still log the token at ERROR — it is the only "
                                + "signal such a run produces. ERRORs seen: " + errors));

        assertTrue(token.contains("Employee " + (ROSTER - 1)) && token.contains("Employee " + ROSTER),
                "the line must name every employee left behind, was: " + token);
        assertTrue(token.contains("2 failed") && token.contains("out of " + ROSTER + " total"), token);

        // The counts have to be ON this line. The returned summary string is discarded:
        // cvtool-sync.xml declares no <end>/<fail> transition, so JBeret never promotes
        // a batchlet's return value to the job exit status — production logs
        // "exitStatus: COMPLETED" for a healthy run, not the summary. Without the synced
        // count here, a night that wrote 128 CVs and a night that wrote none are the
        // same line.
        assertTrue(token.contains("synced"),
                "the alarm must carry how many CVs were actually written, or it cannot "
                        + "distinguish a blip from a dead sync, was: " + token);
    }

    /**
     * The negative half of the assertion above — without it, the token test would pass
     * just as well against a service that logged the token unconditionally.
     */
    @Test
    void aCleanRunLogsNoPartialFailureToken() {
        List<String> errors = captureErrors();

        String result = service(rosterWithFailures(ROSTER, 0)).syncAllBaseCvs();

        assertTrue(result.contains("0 failed"), result);
        assertFalse(errors.stream().anyMatch(m -> m.contains("CVTOOL_SYNC_PARTIAL_FAILURE")),
                "a clean run must stay quiet, ERRORs seen: " + errors);
    }

    // ------------------------------------------------------------ above the cap

    /** One past the cap is a signal, not noise: this job's steady-state failure count is 0. */
    @Test
    void oneFailureAboveTheThresholdFailsTheJob() {
        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(rosterWithFailures(ROSTER, 4)).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("4 failed"), ex.getMessage());
    }

    /**
     * The calibration that matters most. A real run synced 127, failed 9 on a
     * uk_useruuid constraint violation, and reported COMPLETED — the defect that made
     * this job throw on any failure in the first place. The tolerance must not quietly
     * re-admit it.
     */
    @Test
    void theConstraintViolationIncidentStillFailsTheJob() {
        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(rosterWithFailures(136, 9)).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("9 failed"), ex.getMessage());
    }

    /**
     * The percentage cap earning its place: on a short roster the absolute cap of 3
     * would wave through a third of the company. 1 of 6 is 16.7%, well past 5%.
     */
    @Test
    void aSmallRosterIsGovernedByThePercentageCap() {
        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(rosterWithFailures(6, 1)).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("1 failed"), ex.getMessage());
    }

    /**
     * Total collapse must still be loud — the case the whole fail-loudly contract was
     * written for.
     */
    @Test
    void everyEmployeeFailingStillFailsTheJob() {
        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(rosterWithFailures(ROSTER, ROSTER)).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains(ROSTER + " failed"), ex.getMessage());
    }

    /**
     * The tolerance is per-employee only. A failed list fetch means zero CVs were even
     * attempted, and no threshold applies to it — it throws from the fetch itself.
     */
    @Test
    void aFailedListFetchIsNeverTolerated() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenThrow(readTimeout());

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(client).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("Could not fetch employee list"), ex.getMessage());
    }

    // ------------------------------------------------------------------ the cap itself

    /**
     * Guards the calibration as a pair of numbers rather than as prose. The observed
     * transient rate is 2; the known real defect is 9 of 136. A threshold that stops
     * separating those two has stopped doing its job.
     *
     * <p>Read from the {@code @ConfigProperty} defaults rather than from the fixture
     * above, which would merely assert back the values it had just assigned. These are
     * the numbers a deployed environment actually gets when the env vars are unset, so
     * this is the one assertion here that binds the tests to production config — and it
     * fails if someone retunes the defaults without revisiting the two incidents.
     */
    @Test
    void theDefaultCapSeparatesTheObservedBlipFromTheKnownDefect() throws Exception {
        int defaultThreshold = Integer.parseInt(configDefault("syncFailureThreshold"));
        double defaultPercent = Double.parseDouble(configDefault("syncFailurePercent"));

        assertEquals(3, defaultThreshold, "one unit of headroom over the observed 2 — no more");
        assertTrue(2 <= defaultThreshold && 2 <= ROSTER * (defaultPercent / 100.0),
                "the 2-of-148 blip must sit under both caps");
        assertTrue(9 > defaultThreshold,
                "the 9-of-136 constraint incident must sit above the absolute cap");

        // And the fixture the rest of this class runs on must be those same numbers,
        // or every tolerance assertion above is about a configuration nobody deploys.
        CvToolSyncService fixture = service(mock(CvToolClient.class));
        assertEquals(defaultThreshold, fixture.syncFailureThreshold, "fixture has drifted from the default");
        assertEquals(defaultPercent, fixture.syncFailurePercent, "fixture has drifted from the default");
    }

    private static String configDefault(String field) throws NoSuchFieldException {
        return CvToolSyncService.class.getDeclaredField(field)
                .getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class)
                .defaultValue();
    }
}
