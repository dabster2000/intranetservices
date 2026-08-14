package dk.trustworks.intranet.competenceservice.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures what a competence service logs, so the audit-trail assertions can be made in the
 * DB-free fast tier.
 *
 * <p>The module's stable {@code COMPETENCE_*} tokens are not decoration: they are what the
 * production log sweep filters on, and for the authoring surface they are the <em>only</em>
 * record of who published a version, who exported the answer key and whose audience an import
 * moved. A missing {@code actor=} field is therefore a real defect and belongs under a real
 * assertion, not under a code review that has to be repeated by hand.
 *
 * <p>{@code intranetservices/pom.xml} sets {@code java.util.logging.manager} to
 * {@code org.jboss.logmanager.LogManager} for surefire, so the logger behind Lombok's
 * {@code @JBossLog} is a real {@link Logger} and can be observed through the plain JUL API.
 * whether {@code log.infof(...)} arrives formatted or as a format string plus
 * {@link LogRecord#getParameters()} depends on which log manager wins the classpath, so
 * {@link #lines()} renders both shapes to the same string. An assertion written against the
 * rendered line ({@code actor=<uuid>}) therefore holds either way, which is what a test of an
 * audit trail should be asserting anyway: what the sweep will actually read.
 */
final class LogRecorder implements AutoCloseable {

    private final Logger logger;
    private final Level originalLevel;
    private final RecordingHandler handler = new RecordingHandler();

    LogRecorder(Class<?> loggingClass) {
        logger = Logger.getLogger(loggingClass.getName());
        originalLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    /** Every captured line, format string plus arguments. */
    List<String> lines() {
        return handler.snapshot().stream().map(LogRecorder::render).toList();
    }

    /** The captured lines carrying a token, so an assertion cannot pass on the wrong event. */
    List<String> linesWith(String token) {
        return lines().stream().filter(line -> line.contains(token)).toList();
    }

    /** The one line carrying a token, or a failure-friendly {@code null}. */
    String only(String token) {
        List<String> matches = linesWith(token);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(originalLevel);
    }

    private static String render(LogRecord record) {
        String message = record.getMessage();
        Object[] parameters = record.getParameters();
        if (message == null || parameters == null || parameters.length == 0) {
            return String.valueOf(message);
        }
        try {
            return String.format(message, parameters);
        } catch (RuntimeException e) {
            // Not a printf-style line after all — keep the arguments visible rather than
            // silently dropping them, or an assertion could pass on a line it never saw.
            return message + " " + Arrays.toString(parameters);
        }
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
