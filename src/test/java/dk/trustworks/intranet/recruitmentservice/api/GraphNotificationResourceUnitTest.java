package dk.trustworks.intranet.recruitmentservice.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.application.integration.GraphNotificationProcessor;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantInvitationStatus;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.userservice.model.Employee;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link GraphNotificationResource} + {@link GraphNotificationProcessor}.
 *
 * <p>The resource is exercised directly (not via REST Assured) because we want to
 * cover the validation-token handshake and the public-endpoint security model
 * without spinning up a {@code @QuarkusTest}. The processor is exercised via a
 * subclass that overrides the package-private Panache seams — Mockito cannot
 * stub statics inherited from {@code PanacheEntityBase}.
 */
class GraphNotificationResourceUnitTest {

    @Test
    void handshake_echoes_validation_token_as_text_plain() {
        GraphNotificationResource resource = new GraphNotificationResource();
        // Processor is intentionally null — handshake path must not call it.
        Response response = resource.notify("the-token-123", null);

        assertEquals(200, response.getStatus());
        assertEquals("the-token-123", response.getEntity());
        assertEquals(MediaType.TEXT_PLAIN, response.getMediaType().toString());
    }

    @Test
    void clientState_mismatch_is_silent_drop() {
        // The Outlook port should NEVER be called when clientState doesn't match.
        OutlookCalendarPort outlook = Mockito.mock(OutlookCalendarPort.class);
        GraphNotificationProcessor processor = new GraphNotificationProcessor();
        processor.initForTest(outlook, new ObjectMapper(), "expected-secret");

        String body = """
                {
                  "value": [
                    {
                      "clientState": "WRONG-secret",
                      "resourceData": {"id": "evt-1"}
                    }
                  ]
                }
                """;

        // Must not throw — webhook contract requires HTTP 200 even on bad bodies.
        processor.process(body);

        verify(outlook, never()).getAttendeeStatuses(anyString(), anyString());
    }

    @Test
    void valid_clientState_patches_participant_drift() {
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.outlookEventId = "evt-1";
        iv.createdBy = "u-organizer";

        Employee organizer = new Employee();
        organizer.setUuid("u-organizer");
        organizer.setEmail("tam@x");

        Employee attendee = new Employee();
        attendee.setUuid("u-attendee");
        attendee.setEmail("alice@x");

        InterviewParticipant p = new InterviewParticipant();
        p.uuid = "pp-1";
        p.interviewUuid = "iv-1";
        p.userUuid = "u-attendee";
        p.invitationStatus = ParticipantInvitationStatus.INVITED;

        OutlookCalendarPort outlook = Mockito.mock(OutlookCalendarPort.class);
        Mockito.when(outlook.getAttendeeStatuses("tam@x", "evt-1"))
                .thenReturn(List.of(new dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse(
                        "alice@x",
                        dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeStatus.ACCEPTED)));

        GraphNotificationProcessor processor = new GraphNotificationProcessor() {
            @Override
            protected Interview findInterviewByEventId(String eventId) {
                assertEquals("evt-1", eventId);
                return iv;
            }
            @Override
            protected Employee findEmployeeById(String uuid) {
                return "u-organizer".equals(uuid) ? organizer : null;
            }
            @Override
            protected List<Employee> findEmployeesByEmail(String email) {
                return "alice@x".equals(email) ? List.of(attendee) : List.of();
            }
            @Override
            protected List<InterviewParticipant> listParticipants(String interviewUuid) {
                return List.of(p);
            }
        };
        processor.initForTest(outlook, new ObjectMapper(), "expected-secret");

        String body = """
                {
                  "value": [
                    {
                      "clientState": "expected-secret",
                      "resourceData": {"id": "evt-1"}
                    }
                  ]
                }
                """;

        processor.process(body);

        // Drift was patched: INVITED → ACCEPTED.
        assertEquals(ParticipantInvitationStatus.ACCEPTED, p.invitationStatus);
    }

    @Test
    void constantTimeEquals_is_correct() {
        assertFalse(GraphNotificationProcessor.constantTimeEquals(null, "x"));
        assertFalse(GraphNotificationProcessor.constantTimeEquals("x", null));
        assertFalse(GraphNotificationProcessor.constantTimeEquals(null, null));
        assertTrue(GraphNotificationProcessor.constantTimeEquals("secret", "secret"));
        assertFalse(GraphNotificationProcessor.constantTimeEquals("secret", "Secret"));
        assertFalse(GraphNotificationProcessor.constantTimeEquals("secret", "secre"));
        assertFalse(GraphNotificationProcessor.constantTimeEquals("secret", "secrets"));
    }
}
