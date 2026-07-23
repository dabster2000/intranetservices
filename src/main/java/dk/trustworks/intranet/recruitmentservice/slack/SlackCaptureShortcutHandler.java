package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The <b>Log to candidate timeline</b> message shortcut (P14, flag
 * {@code recruitment.slack.capture.enabled}): opens the capture modal
 * with the message text prefilled and editable. Moves hallway PII out of
 * Slack DMs into the governed timeline — the note lands in
 * {@link SlackCaptureSubmitHandler}.
 * <p>
 * Available to every linked, active employee; what they can attach the
 * note TO is visibility-filtered per candidate (the P8 read matrix) in
 * the search and re-checked at submission.
 */
@JBossLog
@ApplicationScoped
public class SlackCaptureShortcutHandler implements SlackInboundHandler {

    public static final String KEY = "recruitment_capture";

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

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
        if (!slackFlags.isCaptureEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        // Channel + ts of the source message ride the modal's metadata so
        // the submission can resolve a permalink for provenance.
        String metadata = new SlackHandlerSupport.ModalMetadata(
                null, request.channelId(), request.messageTs())
                .toJson(objectMapper);
        try {
            slackService.openView(request.triggerId(),
                    SlackRecruitmentViews.captureModal(request.messageText(), metadata));
            return SlackInboundResponse.handled(null);
        } catch (Exception e) {
            log.warnf("Slack capture: views.open failed: %s", e.getMessage());
            return SlackInboundResponse.handled(
                    "The capture form couldn't open — please try the shortcut again.");
        }
    }
}
