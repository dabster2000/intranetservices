package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * The {@code app_mention} Events API handler (P25, Slack spec §5.11):
 * someone mentioned the bot → the @Recruiting assistant answers in the
 * mention's thread. Second Events API key on the P13 allowlist after
 * {@code app_home_opened} — the dispatch pipeline (master gate, actor
 * resolution fail-closed, dedupe) is inherited unchanged.
 *
 * <h3>Gating</h3>
 * The master gate is the dispatch's job (P13); this handler additionally
 * requires {@code recruitment.slack.assistant.enabled} AND
 * {@code recruitment.pipeline.enabled} (the P22/P23 side-surface rule:
 * a dark module leaks no activity, not even to an admin who bypasses the
 * flag on the web). Any gate off ⇒ the mention is silently ignored with
 * 200 — Events API responses carry no user-visible content, so there is
 * no "disabled" ephemeral to send (plan §P25 DoD: "toggle off → mention
 * ignored with 200").
 *
 * <h3>Why async, and why after commit</h3>
 * The answer needs an OpenAI round-trip (intent parse), which must not
 * run inside the dispatch transaction (the §P9 M1 rule — no pooled
 * connection held across the model call) and would blow Slack's
 * 3-second events ack. The handler therefore only checks the gates and
 * schedules the work on a {@code ManagedExecutor} — <b>after the
 * dispatch transaction commits</b> (the recorder's after-commit idiom):
 * a {@code ManagedExecutor} propagates the active JTA context into the
 * task, so a pre-commit submit would share one transaction across two
 * threads ("enlisted connection used without active transaction"), and
 * semantically the assistant must only answer mentions whose dedupe
 * claim actually committed. Consequence: a crash after the ack is NOT
 * retried by Slack — acceptable for a best-effort conversational
 * surface (the P12 AI posture), and the service answers an apology on
 * its own failures.
 */
@JBossLog
@ApplicationScoped
public class SlackAssistantMentionHandler implements SlackInboundHandler {

    /** The Events API event type doubles as the allowlist key (P13 contract). */
    public static final String KEY = "app_mention";

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    SlackAssistantService assistantService;

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
        if (!slackFlags.isAssistantEnabled() || !featureFlag.isPipelineEnabled()) {
            // Silently ignored — events carry no user-visible response.
            return SlackInboundResponse.handled(null);
        }
        String actorUuid = actor.getUuid();
        String channelId = request.channelId();
        String threadTs = request.messageTs();
        String text = request.text();
        submitAfterCommit(() ->
                assistantService.answerMention(actorUuid, channelId, threadTs, text));
        return SlackInboundResponse.handled(null);
    }

    /**
     * Schedule the answer only once the dispatch transaction (and its
     * dedupe claim) has committed; without an active transaction, submit
     * immediately. Mirrors {@code RecruitmentEventRecorder}'s idiom.
     */
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
