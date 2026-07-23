package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.ai.RecruitmentAiDirectory;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralAiSuggestions;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.ReferralService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * The triage ping's <b>Create candidate</b> button (P14, flag
 * {@code recruitment.slack.triage-actions.enabled}): opens the prefilled
 * create-candidate modal for a SUBMITTED referral. AI triage suggestions
 * prefill experience/teamlead exactly like the web queue — same
 * re-validated source ({@link ReferralService#aiSuggestionsForPending}),
 * gated by the same {@code recruitment.ai.referral-triage.enabled}
 * toggle (read inside that accessor).
 */
@JBossLog
@ApplicationScoped
public class SlackTriageCreateButtonHandler implements SlackInboundHandler {

    public static final String KEY = "recruitment_triage_create";

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackTriagePingSupport pingSupport;

    @Inject
    ReferralService referralService;

    @Inject
    RecruitmentAiDirectory aiDirectory;

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
        PendingReferralAiSuggestions ai =
                referralService.aiSuggestionsForPending(referral.getUuid());
        List<SlackRecruitmentViews.TeamleadOption> teamleads = aiDirectory.currentTeamleads().stream()
                .map(option -> new SlackRecruitmentViews.TeamleadOption(option.uuid(), option.name()))
                .toList();
        String metadata = new SlackHandlerSupport.ModalMetadata(
                referral.getUuid(), request.channelId(), request.messageTs())
                .toJson(objectMapper);
        String referrerName = resolveReferrerName(referral);
        try {
            slackService.openView(request.triggerId(),
                    SlackRecruitmentViews.triageCreateModal(
                            referral, referrerName, ai, teamleads, metadata));
            return SlackInboundResponse.handled(null);
        } catch (Exception e) {
            log.warnf("Slack triage-create: views.open failed: %s", e.getMessage());
            return SlackInboundResponse.handled(
                    "The form couldn't open — please click the button again.");
        }
    }

    private String resolveReferrerName(RecruitmentReferral referral) {
        User referrer = referral.getReferrerUuid() == null ? null
                : User.findById(referral.getReferrerUuid());
        return referrer == null ? "unknown" : SlackHandlerSupport.displayName(referrer);
    }
}
