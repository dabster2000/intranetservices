package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Scorecard;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoundUpDecision;
import dk.trustworks.intranet.recruitmentservice.domain.statemachines.InterviewStateMachine;
import dk.trustworks.intranet.recruitmentservice.domain.statemachines.InvalidTransitionException;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate-root service for {@link Interview}.
 *
 * <p>Encapsulates schedule/reschedule/cancel/round-up operations, derives roundNumber
 * monotonically from existing interviews on the same application, persists
 * {@link InterviewParticipant} rows, and delegates cross-aggregate forward-only
 * advancement to {@link InterviewLifecycleService}.
 *
 * <p>State transitions are validated against {@link InterviewStateMachine}; illegal
 * transitions throw {@link InvalidTransitionException}, mapped to HTTP 409 by
 * {@link dk.trustworks.intranet.recruitmentservice.filters.RecruitmentTransitionExceptionMapper}.
 */
@ApplicationScoped
public class InterviewService {

    @Inject InterviewLifecycleService lifecycle;
    @Inject ApplicationService applicationService;
    @Inject OpenRoleService openRoleService;
    @Inject RecruitmentRecordAccessService access;
    @Inject InterviewTemplateCatalog catalog;
    @Inject InterviewStateMachine fsm;

    @Transactional
    public Interview schedule(ScheduleInterviewCommand cmd, String actorUuid) {
        if (cmd.scheduledAt() == null || cmd.scheduledAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("scheduledAt must be in the future");
        }
        if (cmd.roundType() == null) {
            throw new IllegalArgumentException("roundType required");
        }
        if (cmd.applicationUuid() == null || cmd.applicationUuid().isBlank()) {
            throw new IllegalArgumentException("applicationUuid required");
        }

        Application app = Application.findById(cmd.applicationUuid());
        if (app == null) throw new NotFoundException("Application not found");
        if (!access.canSeeApplication(app, actorUuid)) {
            throw new NotFoundException("Application not visible");
        }
        if (app.stage.isTerminal()) {
            throw new InvalidTransitionException(
                    "application is in terminal stage " + app.stage,
                    List.of());
        }
        OpenRole role = OpenRole.findById(app.roleUuid);
        if (role != null && (role.status == RoleStatus.PAUSED || role.status == RoleStatus.CANCELLED)) {
            throw new InvalidTransitionException(
                    "role is " + role.status + "; cannot schedule interview",
                    List.of());
        }

        boolean hasRequired = cmd.participants() != null && cmd.participants().stream().anyMatch(
                p -> p.isRequiredScorer() && p.roleInInterview().canScore());
        if (!hasRequired) {
            throw new IllegalArgumentException(
                    "at least one participant must be a required scorer (LEAD_INTERVIEWER or SCORER)");
        }

        int durationMinutes = resolveDuration(cmd, role);
        if (durationMinutes < 15 || durationMinutes > 240) {
            throw new IllegalArgumentException("durationMinutes must be 15..240");
        }

        long existing = Interview.count("applicationUuid = ?1", cmd.applicationUuid());
        int roundNumber = (int) existing + 1;

        Interview iv = new Interview();
        iv.uuid = UUID.randomUUID().toString();
        iv.applicationUuid = cmd.applicationUuid();
        iv.roundNumber = roundNumber;
        iv.roundType = cmd.roundType();
        iv.scheduledAt = cmd.scheduledAt();
        iv.durationMinutes = durationMinutes;
        iv.status = InterviewStatus.SCHEDULED;
        iv.rescheduleCount = 0;
        iv.persist();

        boolean creatorPresent = cmd.participants().stream()
                .anyMatch(p -> Objects.equals(p.userUuid(), actorUuid));
        for (var p : cmd.participants()) {
            persistParticipant(iv.uuid, p.userUuid(), p.roleInInterview(), p.isRequiredScorer());
        }
        if (!creatorPresent) {
            persistParticipant(iv.uuid, actorUuid, ParticipantRole.TAM, false);
        }

        lifecycle.onScheduled(iv, actorUuid);
        return iv;
    }

    private int resolveDuration(ScheduleInterviewCommand cmd, OpenRole role) {
        if (cmd.durationMinutes() != null) return cmd.durationMinutes();
        if (role == null) return 60;
        var template = catalog.templateFor(role.hiringCategory, cmd.roundType());
        return template != null ? template.defaultDurationMinutes() : 60;
    }

    private void persistParticipant(String interviewUuid, String userUuid, ParticipantRole role, boolean req) {
        InterviewParticipant p = new InterviewParticipant();
        p.uuid = UUID.randomUUID().toString();
        p.interviewUuid = interviewUuid;
        p.userUuid = userUuid;
        p.roleInInterview = role;
        p.isRequiredScorer = req && role.canScore();
        p.persist();
    }

    public Interview findByIdOrThrow(String uuid, String actorUuid) {
        Interview iv = Interview.findById(uuid);
        if (iv == null || !access.canSeeInterview(iv, actorUuid)) {
            throw new NotFoundException("Interview not found");
        }
        return iv;
    }

    public List<Interview> list(InterviewFilter filter, String actorUuid) {
        List<Interview> base = Interview.<Interview>findAll(
                Sort.by("scheduledAt", Sort.Direction.Descending)).list();
        return base.stream()
                .filter(iv -> access.canSeeInterview(iv, actorUuid))
                .filter(filter::matches)
                .toList();
    }

    @Transactional
    public Interview reschedule(String uuid, LocalDateTime newScheduledAt,
                                Integer newDurationMinutes, String actorUuid) {
        Interview iv = findByIdOrThrow(uuid, actorUuid);
        if (iv.status != InterviewStatus.CANCELLED && iv.status != InterviewStatus.SCHEDULED) {
            throw new InvalidTransitionException(
                    "cannot reschedule from " + iv.status,
                    fsm.allowedTransitions(iv.status).stream().map(Enum::name).toList());
        }
        if (newScheduledAt == null || newScheduledAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("newScheduledAt must be in the future");
        }
        iv.scheduledAt = newScheduledAt;
        if (newDurationMinutes != null) iv.durationMinutes = newDurationMinutes;
        if (iv.status == InterviewStatus.CANCELLED) {
            iv.status = InterviewStatus.SCHEDULED;
            iv.rescheduleCount = (iv.rescheduleCount == null ? 0 : iv.rescheduleCount) + 1;
        }
        iv.persist();
        return iv;
    }

    @Transactional
    public Interview cancel(String uuid, String reason, String actorUuid) {
        Interview iv = findByIdOrThrow(uuid, actorUuid);
        if (!fsm.isLegalTransition(iv.status, InterviewStatus.CANCELLED)) {
            throw new InvalidTransitionException(
                    "cannot cancel from " + iv.status,
                    fsm.allowedTransitions(iv.status).stream().map(Enum::name).toList());
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("cancellation requires a reason");
        }
        iv.status = InterviewStatus.CANCELLED;
        iv.persist();
        return iv;
    }

    @Transactional
    public Interview recordRoundUp(String uuid, RoundUpDecision decision, String summary, String actorUuid) {
        Interview iv = findByIdOrThrow(uuid, actorUuid);
        if (!fsm.isLegalTransition(iv.status, InterviewStatus.ROUNDED_UP)) {
            throw new InvalidTransitionException(
                    "cannot round-up from " + iv.status,
                    fsm.allowedTransitions(iv.status).stream().map(Enum::name).toList());
        }
        long required = InterviewParticipant.count(
                "interviewUuid = ?1 and isRequiredScorer = true", uuid);
        long submittedByRequired = Scorecard.count(
                "interviewUuid = ?1 and submittedAt is not null and interviewerUserUuid in "
              + "(select p.userUuid from InterviewParticipant p where p.interviewUuid = ?1 and p.isRequiredScorer = true)",
                uuid);
        if (required > submittedByRequired) {
            throw new InvalidTransitionException(
                    "round-up requires all " + required + " required scorers submitted (got " + submittedByRequired + ")",
                    List.of());
        }
        if (decision == null) throw new IllegalArgumentException("decision required");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary required");
        iv.roundUpDecision = decision;
        iv.roundUpSummary = summary;
        iv.roundUpAt = LocalDateTime.now();
        iv.status = InterviewStatus.ROUNDED_UP;
        iv.persist();
        return iv;
    }
}
