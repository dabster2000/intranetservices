package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.application.integration.OutboxIdempotencyKeys;
import dk.trustworks.intranet.recruitmentservice.application.integration.RecruitmentOutboxService;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookCancelPayload;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookCreatePayload;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookUpdatePayload;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.userservice.model.Employee;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-aggregate lifecycle hooks for {@link Interview}.
 *
 * <p>{@link #onScheduled} runs when an interview is created and:
 * <ol>
 *   <li>The owning OpenRole moves SOURCING → INTERVIEWING (idempotent).</li>
 *   <li>The Application stage advances forward-only to the round-matching stage.</li>
 *   <li>An OUTLOOK_EVENT_CREATE row is enqueued in the external outbox (Slice 3b).</li>
 * </ol>
 *
 * <p>{@link #onRescheduled} and {@link #onCancelled} (Slice 3b) enqueue
 * OUTLOOK_EVENT_UPDATE / OUTLOOK_EVENT_CANCEL rows respectively, so the
 * Microsoft Graph calendar event stays in sync with our truth.
 *
 * <p>{@link #onScorecardSubmitted} runs after each scorecard submission and may
 * trigger an AI {@code SCORECARD_ROUNDUP} artifact request.
 *
 * <p>All hooks are MANDATORY-transactional — they must run inside the caller's
 * write transaction so persistence and outbox-enqueue are atomic.
 */
@ApplicationScoped
public class InterviewLifecycleService {

    private static final Logger LOG = Logger.getLogger(InterviewLifecycleService.class);

    @Inject OpenRoleService openRoleService;
    @Inject ApplicationService applicationService;
    @Inject AiArtifactService aiArtifactService;
    @Inject InterviewParticipantQuery participantQuery;
    @Inject ScorecardQuery scorecardQuery;
    @Inject RecruitmentOutboxService outboxService;

    @ConfigProperty(name = "recruitment.deep-link-base", defaultValue = "https://intra.trustworks.dk")
    String deepLinkBase;

    @Transactional(Transactional.TxType.MANDATORY)
    public void onScheduled(Interview iv, String actorUuid) {
        Application app = applicationService.findByIdOrNull(iv.applicationUuid);
        if (app == null) return;
        OpenRole role = openRoleService.findByIdOrNull(app.roleUuid);
        if (role != null) {
            openRoleService.advanceToInterviewingIfSourcing(role, actorUuid);
        }
        ApplicationStage target = stageForRound(iv.roundType);
        if (target != null) {
            applicationService.advanceStageForwardOnly(app, target, actorUuid);
        }
        enqueueOutlookCreate(iv, actorUuid);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void onRescheduled(Interview iv, int rescheduleCount, String actorUuid) {
        enqueueOutlookUpdate(iv, rescheduleCount, actorUuid);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void onCancelled(Interview iv, String reason, String actorUuid) {
        enqueueOutlookCancel(iv, reason, actorUuid);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void onScorecardSubmitted(Interview iv, String actorUuid) {
        long required = participantQuery.requiredScorerCount(iv.uuid);
        long submitted = scorecardQuery.submittedByRequiredCount(iv.uuid);
        if (required > 0 && submitted >= required) {
            aiArtifactService.requestArtifact(
                AiSubjectKind.INTERVIEW,
                iv.uuid,
                AiArtifactKind.SCORECARD_ROUNDUP,
                Map.of("interviewUuid", iv.uuid),
                actorUuid);
        }
    }

    private void enqueueOutlookCreate(Interview iv, String actorUuid) {
        Employee organizer = findEmployee(actorUuid);
        if (organizer == null || organizer.getEmail() == null) {
            LOG.warnf("onScheduled: actor %s missing email; skipping Outlook enqueue", actorUuid);
            return;
        }
        List<String> attendeeEmails = resolveAttendeeEmails(iv.uuid);
        Instant startUtc = iv.scheduledAt.toInstant(ZoneOffset.UTC);
        Instant endUtc = startUtc.plusSeconds(iv.durationMinutes * 60L);
        String subject = "Interview · round " + iv.roundNumber + " (" + iv.roundType + ")";
        String body = "<p>Interview scheduled — see "
                + deepLinkBase + "/recruitment/interviews/" + iv.uuid + "</p>";
        outboxService.enqueue(
                OutboxKind.OUTLOOK_EVENT_CREATE,
                OutboxIdempotencyKeys.outlookCreate(iv.uuid),
                iv.uuid,
                new OutlookCreatePayload(iv.uuid, organizer.getEmail(), subject, body,
                        startUtc, endUtc, attendeeEmails, true));
    }

    private void enqueueOutlookUpdate(Interview iv, int rescheduleCount, String actorUuid) {
        Employee organizer = findEmployee(actorUuid);
        if (organizer == null || organizer.getEmail() == null || iv.outlookEventId == null) {
            LOG.debugf("onRescheduled: skipping Outlook enqueue (no event id or no organizer email) iv=%s", iv.uuid);
            return;
        }
        List<String> attendeeEmails = resolveAttendeeEmails(iv.uuid);
        Instant startUtc = iv.scheduledAt.toInstant(ZoneOffset.UTC);
        Instant endUtc = startUtc.plusSeconds(iv.durationMinutes * 60L);
        outboxService.enqueue(
                OutboxKind.OUTLOOK_EVENT_UPDATE,
                OutboxIdempotencyKeys.outlookUpdate(iv.uuid, rescheduleCount),
                iv.uuid,
                new OutlookUpdatePayload(iv.uuid, organizer.getEmail(), iv.outlookEventId,
                        startUtc, endUtc, attendeeEmails));
    }

    private void enqueueOutlookCancel(Interview iv, String reason, String actorUuid) {
        Employee organizer = findEmployee(actorUuid);
        if (organizer == null || organizer.getEmail() == null || iv.outlookEventId == null) {
            LOG.debugf("onCancelled: skipping Outlook enqueue (no event id or no organizer email) iv=%s", iv.uuid);
            return;
        }
        outboxService.enqueue(
                OutboxKind.OUTLOOK_EVENT_CANCEL,
                OutboxIdempotencyKeys.outlookCancel(iv.uuid),
                iv.uuid,
                new OutlookCancelPayload(iv.uuid, organizer.getEmail(), iv.outlookEventId, reason));
    }

    private List<String> resolveAttendeeEmails(String interviewUuid) {
        List<InterviewParticipant> participants = listParticipants(interviewUuid);
        List<String> emails = new ArrayList<>();
        for (InterviewParticipant p : participants) {
            Employee e = findEmployee(p.userUuid);
            if (e != null && e.getEmail() != null) emails.add(e.getEmail());
        }
        return emails;
    }

    private ApplicationStage stageForRound(InterviewRoundType rt) {
        return switch (rt) {
            case FIRST        -> ApplicationStage.FIRST_INTERVIEW;
            case CASE_OR_TECH -> ApplicationStage.CASE_OR_TECH_INTERVIEW;
            case FINAL        -> ApplicationStage.FINAL_INTERVIEW;
            case SPECIAL      -> null;
        };
    }

    /* Package-private seam methods — overridable by unit tests because Mockito
     * cannot stub Panache statics inherited from PanacheEntityBase. */

    Employee findEmployee(String userUuid) {
        return Employee.findById(userUuid);
    }

    List<InterviewParticipant> listParticipants(String interviewUuid) {
        return InterviewParticipant.list("interviewUuid = ?1", interviewUuid);
    }
}
