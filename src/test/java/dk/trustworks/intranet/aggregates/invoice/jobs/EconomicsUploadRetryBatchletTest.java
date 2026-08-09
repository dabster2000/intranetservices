package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceEconomicsUpload;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceEconomicsUpload.UploadType;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceEconomicsUploadService;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceEconomicsUploadService.UploadStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Terminal upload failures (FAILED with all retry attempts exhausted) used to exist only as an
 * INFO counter — five vouchers sat invisible in production from February to August 2026 while
 * the retry job reported COMPLETED every 5 minutes. These tests pin the alerting contract:
 * the ECONOMICS_UPLOAD_TERMINAL_FAILED token (which feeds the CloudWatch metric filter in
 * scripts/setup-invoice-booking-alarms.sh) is emitted on every run where terminal failures
 * exist, at ERROR the run an upload first exhausts its retries and as a WARN heartbeat after.
 */
@ExtendWith(MockitoExtension.class)
class EconomicsUploadRetryBatchletTest {

    private static final String ALARM_TOKEN = "ECONOMICS_UPLOAD_TERMINAL_FAILED";
    private static final String INVOICE_UUID = "6a3fe837-7535-47a6-940c-3fe606232d78";
    private static final String COMPANY_UUID = "d8894494-2fb4-4f72-9e05-e6032e6dd691";

    @Mock
    InvoiceEconomicsUploadService uploadService;

    EconomicsUploadRetryBatchlet batchlet;

    private Logger batchletLogger;
    private RecordingHandler logHandler;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        batchlet = new EconomicsUploadRetryBatchlet();
        batchlet.uploadService = uploadService;

        // Surefire installs org.jboss.logmanager.LogManager (see pom.xml), so the logger behind
        // @JBossLog is a java.util.logging.Logger and can be observed through the JUL API.
        batchletLogger = Logger.getLogger(EconomicsUploadRetryBatchlet.class.getName());
        originalLevel = batchletLogger.getLevel();
        batchletLogger.setLevel(Level.ALL);
        logHandler = new RecordingHandler();
        logHandler.setLevel(Level.ALL);
        batchletLogger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        batchletLogger.removeHandler(logHandler);
        batchletLogger.setLevel(originalLevel);
    }

    @Test
    void healthyRunEmitsNoAlarmToken() throws Exception {
        when(uploadService.getUploadStats()).thenReturn(
                new UploadStats(0, 612, 0, 0, 0),
                new UploadStats(0, 612, 0, 0, 0));

        String result = batchlet.process();

        assertEquals("COMPLETED", result);
        assertEquals(List.of(), renderedAtLeast(Level.WARNING),
                "a run with no terminal failures must not emit the alarm token or any WARN/ERROR");
    }

    @Test
    void persistingTerminalFailuresEmitWarnHeartbeatEveryRun() throws Exception {
        when(uploadService.getUploadStats()).thenReturn(
                new UploadStats(0, 612, 5, 0, 5),
                new UploadStats(0, 612, 5, 0, 5));
        when(uploadService.findTerminalFailures()).thenReturn(List.of(terminalUpload()));

        String result = batchlet.process();

        assertEquals("COMPLETED", result);
        List<String> warnings = renderedAt(Level.WARNING);
        assertTrue(warnings.stream().anyMatch(m -> m.contains(ALARM_TOKEN)),
                "the heartbeat must carry the metric-filter token, got: " + warnings);
        assertTrue(warnings.stream().anyMatch(m -> m.contains(INVOICE_UUID)),
                "the heartbeat must name the stuck invoice so the operator can act without a DB query");
        assertEquals(List.of(), renderedAt(Level.SEVERE),
                "a long-standing terminal failure heartbeats at WARN — repeating it at ERROR "
                        + "every 5 minutes is what poisoned ERROR alerting before");
    }

    @Test
    void newlyExhaustedUploadEscalatesToError() throws Exception {
        when(uploadService.getUploadStats()).thenReturn(
                new UploadStats(0, 612, 5, 1, 4),
                new UploadStats(0, 612, 5, 0, 5));
        when(uploadService.findTerminalFailures()).thenReturn(List.of(terminalUpload()));

        batchlet.process();

        List<String> errors = renderedAt(Level.SEVERE);
        assertEquals(1, errors.size(),
                "the run where an upload exhausts its last retry is a new, actionable event "
                        + "and must reach ERROR exactly once, got: " + errors);
        assertTrue(errors.get(0).contains(ALARM_TOKEN));
    }

    @Test
    void alarmTokenIsAbsentFromRoutineStatsLines() throws Exception {
        when(uploadService.getUploadStats()).thenReturn(
                new UploadStats(2, 612, 3, 3, 0),
                new UploadStats(0, 615, 2, 2, 0));

        batchlet.process();

        assertFalse(allRendered().stream().anyMatch(m -> m.contains(ALARM_TOKEN)),
                "retryable (non-terminal) failures are the backoff queue working as designed "
                        + "and must not trip the dead-letter alarm");
    }

    private static InvoiceEconomicsUpload terminalUpload() {
        InvoiceEconomicsUpload upload = new InvoiceEconomicsUpload(
                INVOICE_UUID, COMPANY_UUID, UploadType.ISSUER, 7);
        upload.setAttemptCount(5);
        return upload;
    }

    // --- log capture ---------------------------------------------------------------------------

    /** JBoss-logging *f methods deliver the format string and parameters unformatted. */
    private static String render(LogRecord record) {
        String message = record.getMessage() == null ? "" : record.getMessage();
        Object[] params = record.getParameters();
        return params == null ? message : message + " " + Arrays.toString(params);
    }

    private List<String> allRendered() {
        return logHandler.snapshot().stream().map(EconomicsUploadRetryBatchletTest::render).toList();
    }

    private List<String> renderedAt(Level level) {
        return logHandler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() == level.intValue())
                .map(EconomicsUploadRetryBatchletTest::render)
                .toList();
    }

    private List<String> renderedAtLeast(Level level) {
        return logHandler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() >= level.intValue())
                .map(EconomicsUploadRetryBatchletTest::render)
                .toList();
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }

        List<LogRecord> snapshot() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }
    }
}
