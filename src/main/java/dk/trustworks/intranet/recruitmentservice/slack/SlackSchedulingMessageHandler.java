package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityMessageService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

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
 * {@code ManagedExecutor} after this dispatch commits (the extraction
 * is an OpenAI round-trip; §P9 M1 + the 3 s ack forbid it here).
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
    ManagedExecutor managedExecutor;

    @Inject
    TransactionSynchronizationRegistry txSyncRegistry;

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
        if (text == null || text.isBlank()) {
            // Nothing extractable (emoji, file-only message — files are
            // Phase 13's ingestion path). Silence beats a canned reply
            // to every 👍.
            return SlackInboundResponse.handled(null);
        }
        String actorUuid = actor.getUuid();
        String messageTs = request.messageTs();
        submitAfterCommit(() ->
                messageService.process(actorUuid, channelId, messageTs, text));
        return SlackInboundResponse.handled(null);
    }

    /** The recorder's after-commit idiom — see SlackAssistantMentionHandler. */
    private void submitAfterCommit(Runnable task) {
        try {
            txSyncRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        managedExecutor.submit(task);
                    }
                }
            });
        } catch (Exception e) {
            // No active transaction — direct callers (tests) get the task now.
            managedExecutor.submit(task);
        }
    }
}
