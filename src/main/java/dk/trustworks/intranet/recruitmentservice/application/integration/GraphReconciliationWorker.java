package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantInvitationStatus;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeStatus;
import dk.trustworks.intranet.userservice.model.Employee;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily-cron drift-correction safety net for {@link InterviewParticipant#invitationStatus}.
 *
 * <p>Webhook delivery from Microsoft Graph can drop notifications (network blips,
 * subscription gaps, replay-protection). This worker re-pulls authoritative
 * attendee response statuses from Graph for every upcoming SCHEDULED interview
 * within a 14-day horizon and patches any participant rows that have drifted.
 *
 * <p>Failures on a single interview (organizer not found, Graph error, etc.) are
 * logged and skipped — the worker continues processing the remaining interviews
 * so a single bad row never blocks the whole reconciliation.
 *
 * <p>Panache static seam: {@link #findUpcomingScheduled(LocalDateTime)},
 * {@link #findEmployeeById(String)}, {@link #findEmployeesByEmail(String)}, and
 * {@link #listParticipants(String)} are package-private so unit tests can override
 * them — Mockito cannot stub statics inherited from {@code PanacheEntityBase}.
 */
@ApplicationScoped
public class GraphReconciliationWorker {

    private static final Logger LOG = Logger.getLogger(GraphReconciliationWorker.class);

    @Inject OutlookCalendarPort outlook;

    @Scheduled(every = "24h")
    @Transactional
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime horizon = now.plusDays(14);
        List<Interview> upcoming = findUpcomingScheduled(horizon);
        LOG.debugf("GraphReconciliationWorker: %d upcoming SCHEDULED interview(s) in horizon", upcoming.size());

        for (Interview iv : upcoming) {
            Employee organizer = findEmployeeById(iv.createdBy);
            if (organizer == null || organizer.getEmail() == null) {
                LOG.debugf("Skipping interview %s — organizer (createdBy=%s) missing or has no email",
                        iv.uuid, iv.createdBy);
                continue;
            }
            try {
                List<AttendeeResponse> responses =
                        outlook.getAttendeeStatuses(organizer.getEmail(), iv.outlookEventId);
                List<InterviewParticipant> ps = listParticipants(iv.uuid);
                for (AttendeeResponse r : responses) {
                    ParticipantInvitationStatus mapped = mapStatus(r.status());
                    List<Employee> emps = findEmployeesByEmail(r.email());
                    if (emps.isEmpty()) continue;
                    String uuid = emps.get(0).getUuid();
                    for (InterviewParticipant p : ps) {
                        if (uuid.equals(p.userUuid) && p.invitationStatus != mapped) {
                            LOG.debugf("Patching participant %s on interview %s: %s → %s",
                                    p.uuid, iv.uuid, p.invitationStatus, mapped);
                            p.invitationStatus = mapped;
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.warnf(ex, "Skipping interview %s during reconciliation: %s",
                        iv.uuid, ex.getMessage());
            }
        }
    }

    static ParticipantInvitationStatus mapStatus(AttendeeStatus s) {
        return switch (s) {
            case ACCEPTED -> ParticipantInvitationStatus.ACCEPTED;
            case DECLINED -> ParticipantInvitationStatus.DECLINED;
            case TENTATIVE -> ParticipantInvitationStatus.TENTATIVE;
            case NONE -> ParticipantInvitationStatus.INVITED;
        };
    }

    List<Interview> findUpcomingScheduled(LocalDateTime horizon) {
        return Interview.list(
                "status = ?1 AND scheduledAt <= ?2 AND outlookEventId is not null",
                InterviewStatus.SCHEDULED, horizon);
    }

    Employee findEmployeeById(String uuid) {
        return Employee.findById(uuid);
    }

    List<Employee> findEmployeesByEmail(String email) {
        return Employee.list("email = ?1", email);
    }

    List<InterviewParticipant> listParticipants(String interviewUuid) {
        return InterviewParticipant.list("interviewUuid = ?1", interviewUuid);
    }
}
