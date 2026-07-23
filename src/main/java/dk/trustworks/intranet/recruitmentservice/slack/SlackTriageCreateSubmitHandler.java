package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageResponse;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * The create-candidate modal's {@code view_submission} (P14): runs the
 * SAME one-shot triage command as the web queue
 * ({@link ReferralService#triage} — recruiter tier, one-shot status,
 * optimistic lock, Art. 14 bookkeeping all included) with
 * {@code origin='slack'}, rewrites the ping to its outcome (buttons
 * gone) and swaps the modal for the created-candidate confirmation.
 * <p>
 * Idempotency (DoD): a duplicate submission payload dies in the P13
 * dedupe; a genuinely repeated attempt (new payload, referral already
 * handled) conflicts inside {@code triage} and renders the
 * already-handled outcome view — one command execution, one event,
 * always.
 */
@JBossLog
@ApplicationScoped
public class SlackTriageCreateSubmitHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    ReferralService referralService;

    @Inject
    SlackTriagePingSupport pingSupport;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String key() {
        return SlackRecruitmentViews.TRIAGE_CREATE_SUBMIT;
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
        ReferralTriageRequest triage = new ReferralTriageRequest(
                "CREATE_CANDIDATE",
                state.text(SlackRecruitmentViews.BLOCK_FIRST_NAME),
                state.text(SlackRecruitmentViews.BLOCK_LAST_NAME),
                state.text(SlackRecruitmentViews.BLOCK_EMAIL),
                state.text(SlackRecruitmentViews.BLOCK_PHONE),
                state.text(SlackRecruitmentViews.BLOCK_LINKEDIN),
                null,                                                   // partner sponsorship: web-only
                state.selected(SlackRecruitmentViews.BLOCK_TEAMLEAD),
                null,                                                   // position attach: web-only
                null,
                state.selected(SlackRecruitmentViews.BLOCK_EXPERIENCE));

        ReferralTriageResponse result;
        try {
            result = referralService.triage(
                    parseUuid(metadata.referralUuid()), triage,
                    UUID.fromString(actor.getUuid()), ReferralService.ORIGIN_SLACK);
        } catch (BusinessRuleViolation e) {
            return alreadyHandled(metadata);
        } catch (NotFoundException e) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Referral not found",
                            "This referral no longer exists.")));
        } catch (WebApplicationException e) {
            int status = e.getResponse() == null ? 500 : e.getResponse().getStatus();
            if (status == 400) {
                return SlackInboundResponse.handledWithAction(SlackResponseActions.errors(
                        SlackHandlerSupport.validationErrors(e.getMessage(),
                                SlackRecruitmentViews.BLOCK_FIRST_NAME)));
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
        String candidateName = (nullSafe(triage.firstName()) + " " + nullSafe(triage.lastName())).trim();
        return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                SlackRecruitmentViews.candidateCreatedView(
                        candidateName, result.candidateUuid(), baseUrl)));
    }

    private SlackInboundResponse alreadyHandled(SlackHandlerSupport.ModalMetadata metadata) {
        RecruitmentReferral referral = RecruitmentReferral.findById(metadata.referralUuid());
        if (referral != null) {
            pingSupport.rewriteToOutcome(metadata.channelId(), metadata.messageTs(), referral, null);
        }
        return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                SlackRecruitmentViews.outcomeView("Already handled",
                        "This referral was already triaged by someone else — "
                                + "no duplicate candidate was created.")));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Referral not found");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
