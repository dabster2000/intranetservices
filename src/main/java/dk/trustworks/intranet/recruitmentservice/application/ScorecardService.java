package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Scorecard;
import dk.trustworks.intranet.recruitmentservice.domain.entities.ScorecardAmendment;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.statemachines.InvalidTransitionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate-root service for {@link Scorecard}.
 *
 * <p>Encapsulates submit (one per interviewer per interview), append-only amendments
 * by the original interviewer, admin-only reopen, and the submit-first listing
 * pattern that hides peer scores from required scorers until they have submitted
 * their own scorecard.
 *
 * <p>State enforcement:
 * <ul>
 *   <li>{@link #submit} only runs when the interview is SCHEDULED or HELD; auto-promotes
 *       SCHEDULED → HELD on the first submission (mirrors the practical "interview
 *       just happened, here's my scorecard" flow).</li>
 *   <li>{@link #amend} requires the caller to match {@code interviewerUserUuid}.</li>
 *   <li>{@link #reopen} expects the resource layer to enforce {@code recruitment:admin}.</li>
 * </ul>
 */
@ApplicationScoped
public class ScorecardService {

    @Inject RecruitmentRecordAccessService access;
    @Inject InterviewLifecycleService lifecycle;

    public record ScorecardListResult(boolean requiresSubmission, Scorecard ownScorecard, List<Scorecard> others) {}

    @Transactional
    public Scorecard submit(String interviewUuid, SubmitScorecardCommand cmd, String actorUuid) {
        cmd.validate();

        Interview iv = Interview.findById(interviewUuid);
        if (iv == null || !access.canSeeInterview(iv, actorUuid)) {
            throw new NotFoundException("Interview not found");
        }
        if (iv.status != InterviewStatus.SCHEDULED && iv.status != InterviewStatus.HELD) {
            throw new InvalidTransitionException(
                    "cannot submit scorecard when interview is " + iv.status,
                    List.of());
        }

        InterviewParticipant me = InterviewParticipant.find(
                "interviewUuid = ?1 and userUuid = ?2", interviewUuid, actorUuid).firstResult();
        if (me == null || !me.roleInInterview.canScore()) {
            throw new ForbiddenException("only assigned LEAD_INTERVIEWER or SCORER can submit a scorecard");
        }

        Scorecard existing = Scorecard.find(
                "interviewUuid = ?1 and interviewerUserUuid = ?2", interviewUuid, actorUuid).firstResult();
        if (existing != null) {
            throw new InvalidTransitionException(
                    "scorecard already submitted; use amendments instead", List.of());
        }

        if (iv.status == InterviewStatus.SCHEDULED) {
            iv.status = InterviewStatus.HELD;
            iv.persist();
        }

        Scorecard sc = new Scorecard();
        sc.uuid = UUID.randomUUID().toString();
        sc.interviewUuid = interviewUuid;
        sc.interviewerUserUuid = actorUuid;
        sc.practiceSkillFit = cmd.practiceSkillFit();
        sc.careerLevelFit = cmd.careerLevelFit();
        sc.consultingCommunication = cmd.consultingCommunication();
        sc.clientFacingMaturity = cmd.clientFacingMaturity();
        sc.cultureValueFit = cmd.cultureValueFit();
        sc.deliveryTrackPotential = cmd.deliveryTrackPotential();
        sc.recommendation = cmd.recommendation();
        sc.notes = cmd.notes();
        sc.privateNotes = cmd.privateNotes();
        sc.concerns = cmd.concerns();
        sc.submittedAt = LocalDateTime.now();
        sc.persist();

        lifecycle.onScorecardSubmitted(iv, actorUuid);

        return sc;
    }

    @Transactional
    public ScorecardAmendment amend(String scorecardUuid, String body, String actorUuid) {
        Scorecard sc = Scorecard.findById(scorecardUuid);
        if (sc == null) throw new NotFoundException("Scorecard not found");
        if (!sc.interviewerUserUuid.equals(actorUuid)) {
            throw new ForbiddenException("only the original interviewer can amend their scorecard");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("amendment body required");
        }

        ScorecardAmendment a = new ScorecardAmendment();
        a.uuid = UUID.randomUUID().toString();
        a.scorecardUuid = scorecardUuid;
        a.authorUuid = actorUuid;
        a.body = body;
        a.persist();
        return a;
    }

    @Transactional
    public Scorecard reopen(String scorecardUuid, String reason, String adminActorUuid) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reopen reason required");
        }
        Scorecard sc = Scorecard.findById(scorecardUuid);
        if (sc == null) throw new NotFoundException("Scorecard not found");
        sc.reopenedAt = LocalDateTime.now();
        sc.reopenedByUuid = adminActorUuid;
        sc.reopenedReason = reason;
        sc.persist();
        return sc;
    }

    public ScorecardListResult listForInterview(String interviewUuid, String actorUuid, boolean callerHasAdmin) {
        Interview iv = Interview.findById(interviewUuid);
        if (iv == null || !access.canSeeInterview(iv, actorUuid)) {
            throw new NotFoundException("Interview not found");
        }

        if (callerHasAdmin) {
            List<Scorecard> all = Scorecard.list("interviewUuid", interviewUuid);
            return new ScorecardListResult(false, null, all);
        }

        InterviewParticipant me = InterviewParticipant.find(
                "interviewUuid = ?1 and userUuid = ?2", interviewUuid, actorUuid).firstResult();
        boolean amRequiredScorer = me != null && Boolean.TRUE.equals(me.isRequiredScorer);

        Scorecard own = Scorecard.find(
                "interviewUuid = ?1 and interviewerUserUuid = ?2", interviewUuid, actorUuid).firstResult();

        if (amRequiredScorer && own == null) {
            return new ScorecardListResult(true, null, List.of());
        }

        List<Scorecard> all = Scorecard.list("interviewUuid", interviewUuid);
        List<Scorecard> others = all.stream()
                .filter(s -> !s.interviewerUserUuid.equals(actorUuid))
                .toList();
        return new ScorecardListResult(false, own, others);
    }
}
