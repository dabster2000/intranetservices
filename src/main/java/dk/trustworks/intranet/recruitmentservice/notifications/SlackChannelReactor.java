package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P22 partner-track private channels (plan §P22, Slack companion spec
 * §5.2): every partner-track position gets its own private Slack channel
 * ({@code recr-<slug>-<id>}), created with the admin token, whose
 * membership mirrors the hiring circle — the Slack twin of the module's
 * hard visibility filter. {@link SlackCardReactor} posts the position's
 * living cards here instead of any shared channel.
 * <p>
 * Rules enforced here:
 * <ul>
 *   <li><b>Flags:</b> {@code recruitment.pipeline.enabled} AND
 *       {@code recruitment.slack.partner-channels.enabled}, checked per
 *       event; off ⇒ silent PROCESSED advance, no backfill.</li>
 *   <li><b>One channel per position, ever:</b> the
 *       {@link RecruitmentSlackChannel} projection row is the durable
 *       guard, persisted in its own committed transaction right after the
 *       channel is created — a redelivered event reconciles membership
 *       instead of creating a second channel.</li>
 *   <li><b>Membership ≡ circle:</b> every handled event re-invites the
 *       CURRENT circle (invite tolerates {@code already_in_channel}), so a
 *       position whose {@code POSITION_OPENED} predates the toggle gets
 *       its channel — with the right members — on its next circle event
 *       (the no-retroactive-processing rule, self-healing forward).</li>
 *   <li><b>Failure posture:</b> channel create / invite / kick / archive
 *       throw → the delivery retries (≤3, then durable SKIPPED with the
 *       ops log flagging it); while no channel row exists,
 *       {@link SlackCardReactor} degrades to circle-member DMs, so no
 *       update is ever lost to a Slack hiccup.</li>
 *   <li><b>PII boundary:</b> the header and summary are structural —
 *       position title (mrkdwn-escaped), counts, enum codes. Candidate
 *       content in this channel comes only from the card reactor's
 *       {@link SlackCandidateFacts}-built messages.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class SlackChannelReactor extends RecruitmentReactor {

    public static final String NAME = "slack-partner-channels";

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackService slackService;

    @Override
    public String name() {
        return NAME;
    }

    /** The P12 posture: one live try + two catch-up retries, then durable SKIPPED. */
    @Override
    protected int maxDeliveryAttempts() {
        return 3;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        switch (event.getEventType()) {
            case POSITION_OPENED, CIRCLE_MEMBER_ADDED, CIRCLE_MEMBER_REMOVED, POSITION_CLOSED -> {
            }
            default -> {
                return; // not ours — silent advance
            }
        }
        if (!featureFlag.isPipelineEnabled() || !slackFlags.isPartnerChannelsEnabled()) {
            return; // side effects gated; offset advances, no backfill on later enable
        }
        RecruitmentPosition position = event.getPositionUuid() == null ? null
                : RecruitmentPosition.findById(event.getPositionUuid());
        if (position == null) {
            log.warnf("Channel reactor: %s seq %d without loadable position — skipping",
                    event.getEventType(), event.getSeq());
            return;
        }
        if (position.getHiringTrack() != RecruitmentHiringTrack.PARTNER) {
            return; // only partner-track positions get a private channel
        }
        switch (event.getEventType()) {
            case POSITION_OPENED, CIRCLE_MEMBER_ADDED -> {
                RecruitmentSlackChannel channel = ensureChannel(position);
                if (channel != null) {
                    inviteCurrentCircle(position, channel);
                }
            }
            case CIRCLE_MEMBER_REMOVED -> {
                RecruitmentSlackChannel channel = ensureChannel(position);
                if (channel != null) {
                    inviteCurrentCircle(position, channel); // the removed member is no longer in the circle
                    kickMember(channel, memberUuidOf(event));
                }
            }
            case POSITION_CLOSED -> closeChannel(position);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // Channel lifecycle
    // ------------------------------------------------------------------

    /**
     * The existing live channel, or a freshly created one. Null only when
     * the position already CLOSED its channel (archived — nothing to
     * reconcile). Creation order matters: create → persist the row in its
     * own committed transaction (the durable no-second-channel guard) →
     * post the confidentiality header. Invites happen in the caller and
     * re-run on every event, so a failed invite retried later still
     * converges without re-creating anything.
     */
    private RecruitmentSlackChannel ensureChannel(RecruitmentPosition position) throws Exception {
        RecruitmentSlackChannel existing = RecruitmentSlackChannel.findById(position.getUuid());
        if (existing != null) {
            return existing.getArchivedAt() == null ? existing : null;
        }
        String channelId = slackService.createPrivateChannel(channelName(position));
        QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery(
                                "INSERT IGNORE INTO recruitment_slack_channels "
                                + "(position_uuid, channel_id, archived_at) VALUES (:pos, :channel, NULL)")
                        .setParameter("pos", position.getUuid())
                        .setParameter("channel", channelId)
                        .executeUpdate());
        // Best-effort (swallows): the header is informative, not load-bearing.
        slackService.sendMessage(channelId,
                ":lock: *Confidential — partner-track hiring* for *"
                        + SlackCandidateFacts.mrkdwnSafe(position.getTitle()) + "*.\n"
                        + "Everything in this channel is restricted to the hiring circle. "
                        + "Membership follows the circle automatically — do not invite anyone manually. "
                        + "Candidate updates appear here as living cards; decisions happen in the intranet.");
        log.infof("Channel reactor: created private channel %s for partner position %s",
                channelId, position.getUuid());
        // NOT re-read from the DB: the delivery transaction's REPEATABLE READ
        // snapshot predates the (committed) insert above and would see null.
        return new RecruitmentSlackChannel(position.getUuid(), channelId);
    }

    /** Invite every CURRENT circle member — tolerant of already-in, throws on real failure. */
    private void inviteCurrentCircle(RecruitmentPosition position, RecruitmentSlackChannel channel)
            throws Exception {
        List<RecruitmentCircleMember> members = RecruitmentCircleMember.list(
                "positionUuid = ?1", position.getUuid());
        for (RecruitmentCircleMember member : members) {
            User user = User.findById(member.getUserUuid());
            if (user == null || user.getSlackusername() == null || user.getSlackusername().isBlank()) {
                log.infof("Channel reactor: circle member %s has no Slack link — skipping invite",
                        member.getUserUuid());
                continue;
            }
            slackService.inviteToChannel(user, channel.getChannelId());
        }
    }

    private void kickMember(RecruitmentSlackChannel channel, String memberUuid) throws Exception {
        if (memberUuid == null) {
            return;
        }
        User user = User.findById(memberUuid);
        if (user == null || user.getSlackusername() == null || user.getSlackusername().isBlank()) {
            return; // never invited — nothing to kick
        }
        slackService.kickFromChannel(user, channel.getChannelId());
    }

    /**
     * Summary card + archive + durable {@code archived_at}. Known residual:
     * if the archive call fails, the retry re-posts the summary (the
     * summary must precede the archive — an archived channel accepts no
     * posts). Rare, informative, accepted.
     */
    private void closeChannel(RecruitmentPosition position) throws Exception {
        RecruitmentSlackChannel channel = RecruitmentSlackChannel.findById(position.getUuid());
        if (channel == null || channel.getArchivedAt() != null) {
            return; // never had a channel, or already archived (idempotent)
        }
        slackService.sendMessage(channel.getChannelId(), closingSummary(position));
        slackService.archiveChannel(channel.getChannelId());
        QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery(
                                "UPDATE recruitment_slack_channels SET archived_at = UTC_TIMESTAMP(3) "
                                + "WHERE position_uuid = :pos")
                        .setParameter("pos", position.getUuid())
                        .executeUpdate());
        log.infof("Channel reactor: archived private channel %s for closed position %s",
                channel.getChannelId(), position.getUuid());
    }

    /** Structural outcome counts only — no candidate identity in the summary. */
    private String closingSummary(RecruitmentPosition position) {
        List<RecruitmentApplication> applications = RecruitmentApplication.list(
                "positionUuid = ?1", position.getUuid());
        long hired = applications.stream().filter(a -> a.getStage() == RecruitmentStage.HIRED).count();
        long rejected = applications.stream()
                .filter(a -> a.getTerminal() == RecruitmentApplicationTerminal.REJECTED).count();
        long withdrawn = applications.stream()
                .filter(a -> a.getTerminal() == RecruitmentApplicationTerminal.WITHDRAWN).count();
        long pooled = applications.stream()
                .filter(a -> a.getTerminal() == RecruitmentApplicationTerminal.RETURNED_TO_POOL).count();
        long open = applications.stream()
                .filter(a -> a.getTerminal() == null && a.getStage() != RecruitmentStage.HIRED).count();
        return ":checkered_flag: *Position closed* — *"
                + SlackCandidateFacts.mrkdwnSafe(position.getTitle()) + "*.\n"
                + "Applications: " + hired + " hired · " + rejected + " rejected · "
                + withdrawn + " withdrawn · " + pooled + " returned to pool · " + open + " still open.\n"
                + "This channel is now archived.";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * {@code recr-<slug>-<id4>}: Slack channel names are lowercase,
     * ≤80 chars, no spaces/periods. The uuid suffix makes the name unique
     * by construction (two "Partner" positions can coexist), Danish
     * letters transliterate the conventional way.
     */
    static String channelName(RecruitmentPosition position) {
        String slug = position.getTitle() == null ? "position"
                : position.getTitle().toLowerCase(Locale.ROOT)
                        .replace("æ", "ae").replace("ø", "oe").replace("å", "aa")
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = "position";
        }
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        String suffix = position.getUuid().replace("-", "");
        return "recr-" + slug + "-" + suffix.substring(0, 4);
    }

    private String memberUuidOf(RecruitmentEvent event) {
        if (event.getPayload() == null || event.getPayload().isBlank()) {
            return null;
        }
        try {
            Object value = objectMapper.readValue(event.getPayload(), JSON_OBJECT).get("member_uuid");
            return value == null ? null : value.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
