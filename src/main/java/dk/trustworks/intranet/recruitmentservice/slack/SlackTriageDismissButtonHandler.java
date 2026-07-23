package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The triage ping's <b>Dismiss</b> button (P14, flag
 * {@code recruitment.slack.triage-actions.enabled}): opens the dismissal
 * confirmation modal — reason required, consequences spelled out — for a
 * SUBMITTED referral. The close itself happens in
 * {@link SlackTriageDismissSubmitHandler}.
 */
@JBossLog
@ApplicationScoped
public class SlackTriageDismissButtonHandler implements SlackInboundHandler {

    public static final String KEY = "recruitment_triage_dismiss";

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackTriagePingSupport pingSupport;

    @Inject
    SlackService slackService;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isTriageActionsEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        SlackTriagePingSupport.Actionable actionable = pingSupport.resolveActionable(actor, request);
        if (actionable.deny() != null) {
            return actionable.deny();
        }
        RecruitmentReferral referral = actionable.referral();
        String metadata = new SlackHandlerSupport.ModalMetadata(
                referral.getUuid(), request.channelId(), request.messageTs())
                .toJson(objectMapper);
        User referrer = referral.getReferrerUuid() == null ? null
                : User.findById(referral.getReferrerUuid());
        try {
            slackService.openView(request.triggerId(),
                    SlackRecruitmentViews.triageDismissModal(
                            referral, SlackHandlerSupport.displayName(referrer), metadata));
            return SlackInboundResponse.handled(null);
        } catch (Exception e) {
            log.warnf("Slack triage-dismiss: views.open failed: %s", e.getMessage());
            return SlackInboundResponse.handled(
                    "The confirmation couldn't open — please click the button again.");
        }
    }
}
