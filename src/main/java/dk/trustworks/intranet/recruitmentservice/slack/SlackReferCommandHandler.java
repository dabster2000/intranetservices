package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * {@code /refer} — the Slack twin of the P6 60-second referral form
 * (plan §P14, flag {@code recruitment.slack.refer.enabled}). Opens the
 * referral modal against the command's {@code trigger_id}; the actual
 * submission lands in {@link SlackReferSubmitHandler}.
 * <p>
 * Available to every linked, active employee — exactly like the intranet
 * Refer page (class-level {@code recruitment:refer} is an every-employee
 * scope; the dispatch pipeline's active-user resolution is the gate here).
 */
@JBossLog
@ApplicationScoped
public class SlackReferCommandHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackService slackService;

    @Override
    public String key() {
        return "/refer";
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isReferEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        if (request.triggerId() == null) {
            return SlackInboundResponse.handled("Something went wrong — please try /refer again.");
        }
        try {
            slackService.openView(request.triggerId(), SlackRecruitmentViews.referModal());
            // The modal IS the answer — no ephemeral on top of it.
            return SlackInboundResponse.handled(null);
        } catch (Exception e) {
            log.warnf("Slack /refer: views.open failed: %s", e.getMessage());
            return SlackInboundResponse.handled(
                    "The referral form couldn't open — please try /refer again.");
        }
    }
}
