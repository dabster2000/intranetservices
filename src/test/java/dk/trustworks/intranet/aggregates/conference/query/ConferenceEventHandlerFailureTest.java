package dk.trustworks.intranet.aggregates.conference.query;

import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A projection failure used to escape onto Vert.x's default uncaught-exception logger with
 * no context, which is why the 2026-08-03 lost registration went unnoticed. These tests pin
 * the replacement behaviour: contained, logged at ERROR with recovery coordinates, and no
 * "success" browser event.
 */
class ConferenceEventHandlerFailureTest {

    private ConferenceEventHandler handler;
    private EventBus eventBus;
    private Logger handlerLogger;
    private Level originalLevel;
    private RecordingHandler logHandler;

    @BeforeEach
    void setUp() {
        eventBus = mock(EventBus.class);
        handler = new ConferenceEventHandler();
        handler.eventBus = eventBus;

        // Surefire installs org.jboss.logmanager.LogManager (see pom.xml), so the logger
        // behind @JBossLog is a java.util.logging.Logger and can be observed through JUL.
        handlerLogger = Logger.getLogger(ConferenceEventHandler.class.getName());
        originalLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.ALL);
        logHandler = new RecordingHandler();
        logHandler.setLevel(Level.ALL);
        handlerLogger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.removeHandler(logHandler);
        handlerLogger.setLevel(originalLevel);
    }

    private static String envelope() {
        DomainEventEnvelope env = new DomainEventEnvelope();
        env.setEventType("CREATE_CONFERENCE_PARTICIPANT");
        env.setAggregateId("229fd5a2-9e6d-42eb-9afc-e3926286aebb");
        env.setOccurredAt(Instant.parse("2026-08-03T12:03:36Z"));
        env.setPayload("{\"email\":\"ahogsted@hotmail.com\"}");
        return env.toJson();
    }

    @Test
    void projectionFailureIsContainedAndLoggedAtError() {
        // Reproduces the prod shape: the insert blows up inside the @Transactional service.
        assertDoesNotThrow(() -> handler.apply(envelope(), env -> {
            throw new RuntimeException("Data too long for column 'andet' at row 1");
        }), "the exception must not escape to Vert.x's default handler");

        List<String> errors = errorMessages();
        assertEquals(1, errors.size(), () -> "expected exactly one ERROR line, got: " + errors);

        String logged = errors.get(0);
        assertTrue(logged.contains("CONFERENCE PROJECTION FAILED"),
                () -> "must be greppable/alertable: " + logged);
        assertTrue(logged.contains("229fd5a2-9e6d-42eb-9afc-e3926286aebb"),
                () -> "must name the affected conference: " + logged);
        assertTrue(logged.contains("aggregate_events"),
                () -> "must point at the durable event row so the submission can be replayed: " + logged);
    }

    @Test
    void failedProjectionDoesNotAnnounceSuccessToBrowsers() {
        handler.apply(envelope(), env -> {
            throw new IllegalStateException("rollback");
        });

        verifyNoInteractions(eventBus);
    }

    @Test
    void successfulProjectionPublishesBrowserEventAndLogsNoError() {
        List<String> applied = new ArrayList<>();

        handler.apply(envelope(), env -> applied.add(env.getAggregateId()));

        assertEquals(List.of("229fd5a2-9e6d-42eb-9afc-e3926286aebb"), applied);
        verify(eventBus, times(1)).publish(anyString(), eq("229fd5a2-9e6d-42eb-9afc-e3926286aebb"));
        assertEquals(List.of(), errorMessages(), "a successful projection must log no error");
    }

    @Test
    void unreadableEnvelopeIsContainedAndLogged() {
        assertDoesNotThrow(() -> handler.apply("not json", env -> fail("projection must not run")));

        verifyNoInteractions(eventBus);
        assertEquals(1, errorMessages().size());
        assertTrue(errorMessages().get(0).contains("CONFERENCE PROJECTION FAILED"));
    }

    // ---- JUL capture ----

    private List<String> errorMessages() {
        return logHandler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                .map(ConferenceEventHandlerFailureTest::render)
                .toList();
    }

    /**
     * jboss-logging defers formatting to the handler, so {@code errorf} records arrive with
     * the raw format string plus separate parameters. Re-join both so assertions can look
     * for values that only appear in the arguments.
     */
    private static String render(LogRecord record) {
        String message = String.valueOf(record.getMessage());
        Object[] params = record.getParameters();
        return params == null ? message : message + " " + Arrays.toString(params);
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }

        List<LogRecord> snapshot() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }
    }
}
