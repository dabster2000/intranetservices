package dk.trustworks.intranet.communicationsservice.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Slack app whose Home tab is switched off answers every {@code views.publish}
 * with {@code not_enabled}. That is a permanent app-configuration state, not an
 * incident: it cannot be retried away and there is nothing to page on. It used to
 * be logged at ERROR on every attempt, which made it the only ERROR-level line the
 * backend emitted in a 24h window and so made ERROR-based alerting useless.
 * <p>
 * These tests pin the split: {@code not_enabled} is quiet and actionable, every
 * other Slack failure still reaches ERROR and still throws.
 *
 * @see SlackService#reportPublishFailure(String, String)
 */
class SlackServicePublishViewTest {

    private static final String SLACK_USER_ID = "U012ABCDEF";

    private SlackService service;
    private Logger slackServiceLogger;
    private RecordingHandler logHandler;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        service = new SlackService();

        // Surefire installs org.jboss.logmanager.LogManager (see pom.xml), so the logger behind
        // @JBossLog is a java.util.logging.Logger and can be observed through the JUL API.
        slackServiceLogger = Logger.getLogger(SlackService.class.getName());
        originalLevel = slackServiceLogger.getLevel();
        slackServiceLogger.setLevel(Level.ALL);
        logHandler = new RecordingHandler();
        logHandler.setLevel(Level.ALL);
        slackServiceLogger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        slackServiceLogger.removeHandler(logHandler);
        slackServiceLogger.setLevel(originalLevel);
    }

    @Test
    void disabledHomeTabIsNotLoggedAsError() {
        boolean published = service.reportPublishFailure(SLACK_USER_ID, "not_enabled");

        assertFalse(published, "nothing was published, so the caller must not be told otherwise");
        assertEquals(List.of(), errorMessages(),
                "a Slack app setting must not poison ERROR alerting, got: " + allMessages());
    }

    @Test
    void disabledHomeTabIsReportedOnceAtInfoWithTheRemediation() {
        service.reportPublishFailure(SLACK_USER_ID, "not_enabled");

        List<String> infos = messagesAt(Level.INFO);
        assertEquals(1, infos.size(), "exactly one INFO, got: " + allMessages());
        assertTrue(infos.get(0).contains("api.slack.com/apps"),
                "the operator must be told where to fix it, got: " + infos.get(0));
        assertTrue(infos.get(0).contains("Home Tab"),
                "the operator must be told which setting to flip, got: " + infos.get(0));
        assertTrue(infos.get(0).contains(SLACK_USER_ID),
                "the affected user stays traceable, got: " + infos.get(0));
    }

    @Test
    void repeatedDisabledHomeTabDropsToDebug() {
        service.reportPublishFailure(SLACK_USER_ID, "not_enabled");
        service.reportPublishFailure(SLACK_USER_ID, "not_enabled");
        service.reportPublishFailure(SLACK_USER_ID, "not_enabled");

        assertEquals(1, messagesAt(Level.INFO).size(),
                "a permanent setting is announced once, not once per publish: " + allMessages());
        assertEquals(2, messagesAt(Level.FINE).size(),
                "later hits stay traceable at DEBUG, got: " + allMessages());
        assertEquals(List.of(), errorMessages(), "still no ERROR, got: " + allMessages());
    }

    /**
     * The positive half of the pair. Without this a "must not log ERROR" assertion
     * would also pass if the handler were attached to the wrong logger.
     */
    @Test
    void transientFailureIsStillLoggedAsErrorAndRethrown() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.reportPublishFailure(SLACK_USER_ID, "ratelimited"));

        assertTrue(thrown.getMessage().contains("ratelimited"),
                "callers still see the raw Slack error, got: " + thrown.getMessage());
        assertEquals(1, errorMessages().size(),
                "a genuine Slack failure must stay at ERROR, got: " + allMessages());
        assertTrue(render(errorRecords().get(0)).contains("ratelimited"));
    }

    /**
     * Only {@code not_enabled} is reclassified. A permission gap is a real defect —
     * somebody has to grant the scope — so it keeps the ERROR-and-throw posture.
     */
    @Test
    void otherPermanentFailuresAreStillLoggedAsError() {
        assertThrows(RuntimeException.class,
                () -> service.reportPublishFailure(SLACK_USER_ID, "missing_scope"));

        assertEquals(1, errorMessages().size(),
                "missing_scope is not the case being silenced, got: " + allMessages());
    }

    private List<LogRecord> errorRecords() {
        return logHandler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                .toList();
    }

    private List<String> errorMessages() {
        return errorRecords().stream().map(LogRecord::getMessage).toList();
    }

    private List<String> messagesAt(Level level) {
        return logHandler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() == level.intValue())
                .map(SlackServicePublishViewTest::render)
                .toList();
    }

    private List<String> allMessages() {
        return logHandler.snapshot().stream()
                .map(r -> r.getLevel() + ": " + render(r))
                .toList();
    }

    private static String render(LogRecord record) {
        String message = record.getMessage();
        Object[] parameters = record.getParameters();
        if (message == null || parameters == null || parameters.length == 0) {
            return String.valueOf(message);
        }
        // jboss-logging defers formatting to the handler, so infof()/debugf() arrive unformatted.
        return message + " " + java.util.Arrays.toString(parameters);
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
