package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.ReferralService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.util.UUID;

/**
 * The dismiss modal's {@code view_submission} (P14): closes the referral
 * through the same one-shot {@link ReferralService#triage} DISMISS leg as
 * the web queue ({@code origin='slack'}), rewrites the ping to its
 * dismissed outcome and confirms in the modal. Same conflict semantics as
 * {@link SlackTriageCreateSubmitHandler}.
 */
@JBossLog
@ApplicationScoped
public class SlackTriageDismissSubmitHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    ReferralService referralService;

    @Inject
    SlackTriagePingSupport pingSupport;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public String key() {
        return SlackRecruitmentViews.TRIAGE_DISMISS_SUBMIT;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isTriageActionsEnabled()) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Feature disabled",
                            SlackInboundDispatchService.DISABLED_TEXT)));
        }
        SlackHandlerSupport.ModalMetadata metadata =
                SlackHandlerSupport.ModalMetadata.parse(objectMapper, request.privateMetadata());
        if (metadata.referralUuid() == null) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Something went wrong",
                            "The form lost track of its referral — close this and click the "
                                    + "button on the referral message again.")));
        }
        SlackViewState state = SlackViewState.parse(objectMapper, request.stateValues());
        String reason = state.selected(SlackRecruitmentViews.BLOCK_DISMISS_REASON);
        ReferralTriageRequest triage = new ReferralTriageRequest(
                "DISMISS", null, null, null, null, null, null, null, null, reason, null);
        try {
            referralService.triage(parseUuid(metadata.referralUuid()), triage,
                    UUID.fromString(actor.getUuid()), ReferralService.ORIGIN_SLACK);
        } catch (BusinessRuleViolation e) {
            RecruitmentReferral stale = RecruitmentReferral.findById(metadata.referralUuid());
            if (stale != null) {
                pingSupport.rewriteToOutcome(metadata.channelId(), metadata.messageTs(), stale, null);
            }
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Already handled",
                            "This referral was already handled by someone else.")));
        } catch (NotFoundException e) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Referral not found",
                            "This referral no longer exists.")));
        } catch (WebApplicationException e) {
            int status = e.getResponse() == null ? 500 : e.getResponse().getStatus();
            if (status == 400) {
                return SlackInboundResponse.handledWithAction(SlackResponseActions.errors(
                        SlackHandlerSupport.validationErrors(e.getMessage(),
                                SlackRecruitmentViews.BLOCK_DISMISS_REASON)));
            }
            if (status == 403) {
                return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                        SlackRecruitmentViews.outcomeView("No permission",
                                "Referral triage is reserved for the recruitment team "
                                        + "(HR, CXO or admin).")));
            }
            throw e;
        }

        RecruitmentReferral referral = RecruitmentReferral.findById(metadata.referralUuid());
        if (referral != null) {
            pingSupport.rewriteToOutcome(metadata.channelId(), metadata.messageTs(), referral, actor);
        }
        return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                SlackRecruitmentViews.referralDismissedView(
                        SlackRecruitmentViews.dismissReasonLabel(reason == null ? "" : reason))));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Referral not found");
        }
    }
}
