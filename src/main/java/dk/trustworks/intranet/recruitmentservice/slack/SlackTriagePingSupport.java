package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Shared mechanics of the two triage buttons (P14): the actionability
 * gauntlet a click must pass before a modal opens, and the outcome
 * rewrite that removes the buttons from the ping once a referral is
 * handled ({@code chat.update} — the DoD's double-handling guard at the
 * UI level; the real guard is {@code ReferralService.triage}'s
 * status check + optimistic lock beneath).
 */
@JBossLog
@ApplicationScoped
public class SlackTriagePingSupport {

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    SlackService slackService;

    /** Either a referral to act on or the response that ends the click. */
    public record Actionable(RecruitmentReferral referral, SlackInboundResponse deny) {
    }

    /**
     * The click gauntlet: recruiter tier (the same tier
     * {@code ReferralService.triage} enforces — checked early for a
     * friendly ephemeral instead of a dead modal), referral existence
     * (the button value is a CLAIM — never trusted), and one-shot status.
     * A stale click on an already-handled referral also rewrites the ping
     * to its outcome so the buttons stop inviting clicks.
     */
    public Actionable resolveActionable(User actor, SlackInboundRequest request) {
        if (!visibility.isRecruiterTier(actor.getUuid())) {
            return new Actionable(null, SlackInboundResponse.handled(
                    "Referral triage is reserved for the recruitment team (HR, CXO or admin)."));
        }
        String referralUuid = request.actionValue();
        RecruitmentReferral referral = referralUuid == null ? null
                : RecruitmentReferral.findById(referralUuid);
        if (referral == null) {
            return new Actionable(null, SlackInboundResponse.handled(
                    "This referral no longer exists."));
        }
        if (referral.getStatus() != RecruitmentReferralStatus.SUBMITTED) {
            // Stale ping (e.g. handled from the web queue) — rewrite it to
            // its outcome so the buttons disappear, and tell the clicker.
            rewriteToOutcome(request.channelId(), request.messageTs(), referral, null);
            return new Actionable(null, SlackInboundResponse.handled(
                    "This referral was already handled — the message has been updated."));
        }
        return new Actionable(referral, null);
    }

    /**
     * Rewrites the new-referral ping to its outcome line and strips the
     * buttons. Best-effort (chat.update only touches bot-authored
     * messages; content is a fixed template) — the intranet stays the
     * system of record either way.
     *
     * @param actor the handling recruiter, or null when the outcome is
     *              derived from the referral row (stale-click rewrite)
     */
    public void rewriteToOutcome(String channelId, String messageTs,
                                 RecruitmentReferral referral, User actor) {
        SlackHandlerSupport.ModalMetadata ref =
                new SlackHandlerSupport.ModalMetadata(null, channelId, messageTs);
        if (!ref.hasValidPingRef()) {
            log.debug("Slack triage: no valid ping reference — skipping outcome rewrite");
            return;
        }
        String name = SlackCandidateFacts.mrkdwnSafe(referral.getCandidateName());
        String outcome = switch (referral.getStatus()) {
            case TRIAGED, CONVERTED -> ":white_check_mark: Triaged"
                    + (actor == null ? "" : " by " + SlackHandlerSupport.displayName(actor))
                    + " — candidate created.";
            case CLOSED -> ":no_entry_sign: Dismissed"
                    + (actor == null ? "" : " by " + SlackHandlerSupport.displayName(actor)) + ".";
            case SUBMITTED -> null; // nothing to rewrite — still actionable
        };
        if (outcome == null) {
            return;
        }
        String text = ":raised_hands: *New referral* — " + name + ". " + outcome;
        slackService.updateMessage(channelId, messageTs, text, null);
    }
}
