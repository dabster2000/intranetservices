package dk.trustworks.intranet.recruitmentservice.application.integration.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookCreatePayload;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import dk.trustworks.intranet.recruitmentservice.infrastructure.OutlookCalendarException;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CreateEventCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutlookCreateHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static OutlookCreateHandler handlerWith(Map<String, Interview> directory, OutlookCalendarPort port) {
        OutlookCreateHandler h = new OutlookCreateHandler() {
            @Override Interview findInterview(String uuid) { return directory.get(uuid); }
        };
        h.outlook = port;
        h.mapper = JSON;
        return h;
    }

    private static RecruitmentOutboxRow rowFor(OutlookCreatePayload p) throws Exception {
        RecruitmentOutboxRow r = RecruitmentOutboxRow.create(
                OutboxKind.OUTLOOK_EVENT_CREATE, "k-" + p.interviewUuid(), p.interviewUuid(),
                JSON.writeValueAsString(p));
        return r;
    }

    private static OutlookCreatePayload samplePayload() {
        return new OutlookCreatePayload(
                "iv-1", "organizer@trustworks.dk",
                "Interview", "<p>hi</p>",
                Instant.parse("2026-05-01T09:00:00Z"),
                Instant.parse("2026-05-01T10:00:00Z"),
                List.of("alice@x.dk"),
                true);
    }

    @Test
    void ok_when_port_returns_event_id_and_writes_back_outlookEventId() throws Exception {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.status = InterviewStatus.SCHEDULED;
        Map<String, Interview> dir = new HashMap<>();
        dir.put("iv-1", iv);

        OutlookCalendarPort port = mock(OutlookCalendarPort.class);
        when(port.createEvent(any(CreateEventCommand.class))).thenReturn("evt-99");

        OutlookCreateHandler h = handlerWith(dir, port);
        HandlerResult result = h.handle(rowFor(samplePayload()));

        assertEquals(HandlerOutcome.OK, result.outcome());
        assertEquals("evt-99", iv.outlookEventId);
        verify(port).createEvent(any(CreateEventCommand.class));
    }

    @Test
    void retryable_when_port_throws_retryable_exception() throws Exception {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.status = InterviewStatus.SCHEDULED;
        Map<String, Interview> dir = new HashMap<>();
        dir.put("iv-1", iv);

        OutlookCalendarPort port = mock(OutlookCalendarPort.class);
        when(port.createEvent(any(CreateEventCommand.class)))
                .thenThrow(new OutlookCalendarException(true, "GRAPH_5XX", "upstream blip"));

        OutlookCreateHandler h = handlerWith(dir, port);
        HandlerResult result = h.handle(rowFor(samplePayload()));

        assertEquals(HandlerOutcome.RETRYABLE, result.outcome());
        assertNull(iv.outlookEventId);
    }

    @Test
    void terminal_when_port_throws_terminal_exception() throws Exception {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.status = InterviewStatus.SCHEDULED;
        Map<String, Interview> dir = new HashMap<>();
        dir.put("iv-1", iv);

        OutlookCalendarPort port = mock(OutlookCalendarPort.class);
        when(port.createEvent(any(CreateEventCommand.class)))
                .thenThrow(new OutlookCalendarException(false, "GRAPH_403", "forbidden"));

        OutlookCreateHandler h = handlerWith(dir, port);
        HandlerResult result = h.handle(rowFor(samplePayload()));

        assertEquals(HandlerOutcome.TERMINAL, result.outcome());
        assertNull(iv.outlookEventId);
    }

    @Test
    void ok_skipped_when_interview_cancelled_and_port_not_called() throws Exception {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.status = InterviewStatus.CANCELLED;
        Map<String, Interview> dir = new HashMap<>();
        dir.put("iv-1", iv);

        OutlookCalendarPort port = mock(OutlookCalendarPort.class);

        OutlookCreateHandler h = handlerWith(dir, port);
        HandlerResult result = h.handle(rowFor(samplePayload()));

        assertEquals(HandlerOutcome.OK, result.outcome());
        assertNull(iv.outlookEventId);
        verify(port, never()).createEvent(any());
    }

    @Test
    void ok_skipped_when_interview_missing() throws Exception {
        OutlookCalendarPort port = mock(OutlookCalendarPort.class);
        OutlookCreateHandler h = handlerWith(new HashMap<>(), port);

        HandlerResult result = h.handle(rowFor(samplePayload()));

        assertEquals(HandlerOutcome.OK, result.outcome());
        verify(port, never()).createEvent(any());
    }
}
