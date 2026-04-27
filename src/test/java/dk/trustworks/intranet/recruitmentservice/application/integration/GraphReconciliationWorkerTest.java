package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantInvitationStatus;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeStatus;
import dk.trustworks.intranet.userservice.model.Employee;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GraphReconciliationWorker}. Subclasses the worker to
 * override the package-private Panache seams; mocks the {@link OutlookCalendarPort}
 * with Mockito.
 */
class GraphReconciliationWorkerTest {

    @Test
    void patches_invitation_status_when_drift_detected() {
        // Interview scheduled within the 14d horizon, organizer = u-organizer.
        Interview iv = new Interview();
        iv.uuid = "iv-1";
        iv.applicationUuid = "app-1";
        iv.scheduledAt = LocalDateTime.now().plusDays(3);
        iv.status = InterviewStatus.SCHEDULED;
        iv.outlookEventId = "evt-1";
        iv.createdBy = "u-organizer";

        // Participant currently INVITED — Graph says ACCEPTED, drift!
        InterviewParticipant p = new InterviewParticipant();
        p.uuid = "pp-1";
        p.interviewUuid = "iv-1";
        p.userUuid = "u-attendee";
        p.invitationStatus = ParticipantInvitationStatus.INVITED;

        Employee organizer = new Employee();
        organizer.setUuid("u-organizer");
        organizer.setEmail("tam@x");

        Employee attendee = new Employee();
        attendee.setUuid("u-attendee");
        attendee.setEmail("alice@x");

        OutlookCalendarPort outlook = mock(OutlookCalendarPort.class);
        when(outlook.getAttendeeStatuses("tam@x", "evt-1"))
                .thenReturn(List.of(new AttendeeResponse("alice@x", AttendeeStatus.ACCEPTED)));

        GraphReconciliationWorker worker = new GraphReconciliationWorker() {
            @Override
            List<Interview> findUpcomingScheduled(LocalDateTime horizon) {
                return List.of(iv);
            }
            @Override
            Employee findEmployeeById(String uuid) {
                return "u-organizer".equals(uuid) ? organizer : null;
            }
            @Override
            List<Employee> findEmployeesByEmail(String email) {
                return "alice@x".equals(email) ? List.of(attendee) : List.of();
            }
            @Override
            List<InterviewParticipant> listParticipants(String interviewUuid) {
                assertEquals("iv-1", interviewUuid);
                return List.of(p);
            }
        };
        worker.outlook = outlook;

        worker.reconcile();

        // Drift was patched: INVITED → ACCEPTED.
        assertEquals(ParticipantInvitationStatus.ACCEPTED, p.invitationStatus);
    }
}
