package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;

/**
 * The <b>Scoring guide</b> button inside the Slack scorecard modal (the
 * on-demand companion of {@link SlackScorecardOpenButtonHandler}): pushes a
 * second view with the full guidance — per-subject definitions and what each
 * score looks like — so the scorecard form itself carries only one-line
 * hints.
 * <p>
 * The button's {@code value} (the interview uuid) round-trips through Slack
 * and is therefore a CLAIM — re-verified fail-closed exactly like the open
 * handler: the interview must exist, be an active ROUND, and the actor must
 * be one of its assigned interviewers. Unlike the open handler there is no
 * already-submitted gate: the guide shows no scores and reveals nothing, and
 * the only way to reach the button is a scorecard modal that was legitimately
 * open. Every deny path pushes a uniform notice view — a forged value learns
 * nothing beyond "not available".
 */
@JBossLog
@ApplicationScoped
public class SlackScorecardGuideButtonHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackService slackService;

    @Override
    public String key() {
        return SlackRecruitmentViews.SCORECARD_GUIDE_OPEN;
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
            return notice(request, SlackScorecardOpenButtonHandler.NOT_AVAILABLE_TEXT);
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(interview.getApplicationUuid());
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        List<ScorecardAttribute> template =
                position == null ? null : position.getScorecardTemplate();
        if (template == null || template.isEmpty()) {
            return notice(request, SlackScorecardOpenButtonHandler.NOT_AVAILABLE_TEXT);
        }
        try {
            // The click comes from inside the scorecard modal, so the guide
            // stacks on top of it (views.push) — Close pops back to the form.
            slackService.pushView(request.triggerId(),
                    SlackRecruitmentViews.scorecardGuideView(template));
            return SlackInboundResponse.handled(null);
        } catch (Exception e) {
            log.warnf("Slack scorecard-guide: views.push failed: %s", e.getMessage());
            return SlackInboundResponse.handled(null);
        }
    }

    /**
     * Deny notice. The button lives inside a modal, so there is no
     * {@code response_url} — push a small outcome view onto the stack
     * instead (best-effort); fall back to the response text otherwise.
     */
    private SlackInboundResponse notice(SlackInboundRequest request, String text) {
        if (request.triggerId() != null) {
            try {
                slackService.pushView(request.triggerId(),
                        SlackRecruitmentViews.outcomeView("Scoring guide", text));
                return SlackInboundResponse.handled(null);
            } catch (Exception e) {
                log.debugf("Slack scorecard-guide: outcome view failed: %s", e.getMessage());
            }
        }
        return SlackInboundResponse.handled(text);
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
}
