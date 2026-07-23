package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.ReferralService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * The {@code /refer} modal's {@code view_submission} (P14): executes the
 * SAME command as {@code POST /recruitment/referrals}
 * ({@link ReferralService#submit} — all P6 validation applies) with
 * {@code origin='slack'}, then swaps the modal for a confirmation view
 * carrying the <em>My referrals</em> deep link.
 * <p>
 * Validation errors from the service (bad LinkedIn host, malformed email…)
 * render inline via {@code response_action: errors} anchored to the
 * offending input block — the DoD's inline-error contract.
 */
@JBossLog
@ApplicationScoped
public class SlackReferSubmitHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    ReferralService referralService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String key() {
        return SlackRecruitmentViews.REFER_SUBMIT;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isReferEnabled()) {
            // Mid-flight toggle-off: the modal is open — the update view is
            // the only channel that still renders.
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Feature disabled",
                            SlackInboundDispatchService.DISABLED_TEXT)));
        }
        SlackViewState state = SlackViewState.parse(objectMapper, request.stateValues());
        ReferralCreateRequest create = new ReferralCreateRequest(
                state.text(SlackRecruitmentViews.BLOCK_CANDIDATE_NAME),
                state.text(SlackRecruitmentViews.BLOCK_LINKEDIN),
                state.text(SlackRecruitmentViews.BLOCK_EMAIL),
                state.selected(SlackRecruitmentViews.BLOCK_RELATION),
                state.text(SlackRecruitmentViews.BLOCK_EXTERNAL_REFERRER),
                state.text(SlackRecruitmentViews.BLOCK_WHY));
        try {
            referralService.submit(create, UUID.fromString(actor.getUuid()),
                    ReferralService.ORIGIN_SLACK);
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 400) {
                return SlackInboundResponse.handledWithAction(SlackResponseActions.errors(
                        SlackHandlerSupport.validationErrors(e.getMessage(),
                                SlackRecruitmentViews.BLOCK_CANDIDATE_NAME)));
            }
            throw e;
        }
        return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                SlackRecruitmentViews.referSubmittedView(baseUrl)));
    }
}
