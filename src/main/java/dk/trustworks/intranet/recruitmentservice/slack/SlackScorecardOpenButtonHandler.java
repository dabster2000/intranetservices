package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;

/**
 * The <b>Fill in scorecard</b> button on the SLA nudge DM and the
 * interview-kit DM (P18, flag {@code recruitment.slack.scorecard.enabled},
 * Slack spec §5.6): opens the full scorecard modal for the referenced
 * interview.
 * <p>
 * The button's {@code value} (the interview uuid) is round-tripped through
 * Slack and therefore a CLAIM — this handler re-verifies, fail-closed, that
 * the interview exists, is an active ROUND, and that the <em>actor</em> is
 * an assigned interviewer who has not already submitted (never trusts the
 * DM's original audience). Every deny path answers a uniform notice with
 * zero side effects — a forged value learns nothing beyond "not available".
 */
@JBossLog
@ApplicationScoped
public class SlackScorecardOpenButtonHandler implements SlackInboundHandler {

    /** Uniform deny — covers unknown/cancelled/informal/not-assigned alike. */
    static final String NOT_AVAILABLE_TEXT =
            "That scorecard isn't available to you — the interview may have been "
                    + "cancelled, or you're not one of its assigned interviewers.";

    static final String ALREADY_SUBMITTED_TEXT =
            "You've already submitted your scorecard for this interview — "
                    + "nothing more to do here. You can read the debrief in the intranet.";

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackService slackService;

    @Override
    public String key() {
        return SlackRecruitmentViews.SCORECARD_OPEN;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isScorecardEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        RecruitmentInterview interview = resolveInterview(request.actionValue());
        if (interview == null
                || interview.getKind() != RecruitmentInterviewKind.ROUND
                || !interview.isActive()
                || !interview.isAssigned(actor.getUuid())) {
            return SlackInboundResponse.handled(NOT_AVAILABLE_TEXT);
        }
        if (RecruitmentScorecard.count("interviewUuid = ?1 and interviewerUuid = ?2",
                interview.getUuid(), actor.getUuid()) > 0) {
            return SlackInboundResponse.handled(ALREADY_SUBMITTED_TEXT);
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(interview.getApplicationUuid());
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        List<ScorecardAttribute> template =
                position == null ? null : position.getScorecardTemplate();
        if (application == null || position == null || template == null || template.isEmpty()) {
            // A round without a template can't be scored anywhere — the web
            // form would refuse too. Structurally broken data, not authz.
            return SlackInboundResponse.handled(NOT_AVAILABLE_TEXT);
        }
        RecruitmentCandidate candidate =
                RecruitmentCandidate.findById(application.getCandidateUuid());
        String candidateName = candidateName(candidate);
        try {
            slackService.openView(request.triggerId(),
                    SlackRecruitmentViews.scorecardModal(candidateName, position.getTitle(),
                            interview.getRound(), template, interview.getUuid()));
            return SlackInboundResponse.handled(null);
        } catch (Exception e) {
            log.warnf("Slack scorecard-open: views.open failed: %s", e.getMessage());
            return SlackInboundResponse.handled(
                    "The scorecard couldn't open — please click the button again.");
        }
    }

    private static RecruitmentInterview resolveInterview(String actionValue) {
        if (actionValue == null || actionValue.isBlank()) {
            return null;
        }
        try {
            UUID.fromString(actionValue.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return RecruitmentInterview.findById(actionValue.trim());
    }

    static String candidateName(RecruitmentCandidate candidate) {
        if (candidate == null) {
            return "the candidate";
        }
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "the candidate" : name;
    }
}
