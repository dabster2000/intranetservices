package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Map;

/**
 * Cross-aggregate lifecycle hooks for {@link Interview}.
 *
 * <p>{@link #onScheduled(Interview, String)} runs when an interview is created
 * or rescheduled and propagates two derived facts:
 * <ol>
 *   <li>The owning OpenRole moves SOURCING → INTERVIEWING (idempotent).</li>
 *   <li>The Application stage advances forward-only to the round-matching
 *       stage (FIRST_INTERVIEW / CASE_OR_TECH_INTERVIEW / FINAL_INTERVIEW).</li>
 * </ol>
 *
 * <p>{@link #onScorecardSubmitted(Interview, String)} runs after each scorecard
 * submission. When the count of submitted scorecards reaches the count of
 * required scorers, an AI {@code SCORECARD_ROUNDUP} artifact is requested.
 *
 * <p>Both hooks are MANDATORY-transactional — they must run inside the caller's
 * write transaction so persistence and side-effects are atomic.
 */
@ApplicationScoped
public class InterviewLifecycleService {

    @Inject OpenRoleService openRoleService;
    @Inject ApplicationService applicationService;
    @Inject AiArtifactService aiArtifactService;
    @Inject InterviewParticipantQuery participantQuery;
    @Inject ScorecardQuery scorecardQuery;

    /**
     * Called when an interview transitions into SCHEDULED (creation or
     * reschedule). Auto-advances OpenRole and Application stage; both ops are
     * forward-only and idempotent.
     */
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
    }

    /**
     * Called after each Scorecard submission. Auto-fires SCORECARD_ROUNDUP when
     * the number of submitted scorecards meets/exceeds the number of required
     * scorers configured on the interview.
     */
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

    private ApplicationStage stageForRound(InterviewRoundType rt) {
        return switch (rt) {
            case FIRST        -> ApplicationStage.FIRST_INTERVIEW;
            case CASE_OR_TECH -> ApplicationStage.CASE_OR_TECH_INTERVIEW;
            case FINAL        -> ApplicationStage.FINAL_INTERVIEW;
            case SPECIAL      -> null;
        };
    }
}
