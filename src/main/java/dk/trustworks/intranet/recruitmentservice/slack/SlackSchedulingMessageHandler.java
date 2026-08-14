package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityMessageService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.SchedulingAsyncRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The {@code message.im} Events API handler (Method B Phase 12, plan
 * §12.2): an interviewer typed free text in their DM with the bot. The
 * dispatch pipeline is inherited unchanged — and it already carries the
 * two loads this event type brings:
 * <ul>
 *   <li><b>Bot echoes:</b> {@code message.im} fires for the bot's OWN
 *       DMs too; their {@code user} is the bot's Slack id, which no
 *       intranet user is linked to, so they die fail-closed at actor
 *       resolution — before the dedupe claim, before this handler.</li>
 *   <li><b>Retries:</b> the events dedupe claim on {@code event_id}
 *       absorbs Slack's up-to-3× redelivery.</li>
 * </ul>
 * Gate: the Method B flag only — a dark module leaks no activity, and
 * events carry no user-visible response, so off simply means silence
 * (the P25 events posture). Everything else — correlation, extraction,
 * confirmation — happens in {@link AvailabilityMessageService} on the
 * {@link SchedulingAsyncRunner}'s plain thread after this dispatch
 * commits (the extraction is an OpenAI round-trip; §P9 M1 + the 3 s
 * ack forbid it here, and a ManagedExecutor would drag the committed
 * transaction context along — finding F10).
 */
@JBossLog
@ApplicationScoped
public class SlackSchedulingMessageHandler implements SlackInboundHandler {

    /** The Events API event type doubles as the allowlist key (P13 contract). */
    public static final String KEY = "message";

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    AvailabilityMessageService messageService;

    @Inject
    SchedulingAsyncRunner asyncRunner;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!methodBFlag.isMethodBEnabled()) {
            return SlackInboundResponse.handled(null);
        }
        String channelId = request.channelId();
        if (channelId == null || !channelId.startsWith("D")) {
            // message.im is DM-only today; if a broader message.*
            // subscription ever appears, channel surfaces stay ignored.
            return SlackInboundResponse.handled(null);
        }
        String text = request.text();
        java.util.List<dk.trustworks.intranet.recruitmentservice.dto
                .SlackInboundRequest.FileRef> files = request.files();
        boolean hasFiles = files != null && !files.isEmpty();
        if ((text == null || text.isBlank()) && !hasFiles) {
            // Nothing extractable (a bare emoji). Silence beats a canned
            // reply to every 👍.
            return SlackInboundResponse.handled(null);
        }
        String actorUuid = actor.getUuid();
        String messageTs = request.messageTs();
        asyncRunner.submitAfterCommit(() ->
                messageService.process(actorUuid, channelId, messageTs, text, files));
        return SlackInboundResponse.handled(null);
    }
}
