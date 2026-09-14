package dk.trustworks.intranet.aggregates.crm.slack.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What one general Slack channel said about one client on one Copenhagen calendar day
 * (spec §4.5, §5.2), as the account's timeline shows it.
 *
 * <p><b>There is no message text field, and that is deliberate</b> — the same refusal
 * {@link AccountSlackDigest} inherited from V588 and V594, and it matters more here than
 * there. A general channel carries colleagues' health, whereabouts, absences and jokes in
 * between the four sentences that are about accounts; all of it is held in memory for the
 * length of one model call and then gone. What is stored is the model's STRUCTURED reading
 * after {@code SlackMentionExtractionService} has re-validated, capped and stripped it.
 * A future change that adds a text column here is undoing a decision, not filling a gap.
 *
 * <p><b>One row per (client, channel, day).</b> {@link #uuid} is derived deterministically
 * from the triple so a re-read updates rather than duplicates, and the same day of two
 * different channels stays two rows — which channel said it is part of what the row means.
 *
 * <p>{@link #messageCount} counts the distinct cited messages about THIS client, not the
 * channel's traffic that day. A day of 200 messages in which one sentence concerned Novo
 * is a mention with a count of one, and the number the timeline shows has to be that one.
 *
 * <p>{@link #dismissedBy} / {@link #dismissedAt} are set by a person saying "this is not
 * about this client" and are <b>never written by the sync</b>: an upsert onto a dismissed
 * row refreshes the reading and leaves the dismissal alone, so a re-read cannot resurrect
 * something somebody already rejected. A dismissed row is hidden from the feed and from
 * the relationship graph and kept for audit; there is no restore endpoint.
 *
 * <p>Who from Trustworks wrote the cited lines lives in
 * {@link AccountSlackMentionParticipant}. The agreed retention is 24 months after the
 * account's last activity — the purge job is NOT built, and this table must be on its list
 * when it is.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_slack_mention")
public class AccountSlackMention extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    @Column(name = "channel_id", length = 32, nullable = false)
    private String channelId;

    /** Denormalised: a rename, or a channel somebody removed, must not rewrite last month's feed. */
    @Column(name = "channel_name", length = 80, nullable = false)
    private String channelName;

    @Column(name = "mention_date", nullable = false)
    private LocalDate mentionDate;

    /** Distinct cited messages about this client — never the channel-day total. */
    @Column(name = "message_count", nullable = false)
    private int messageCount;

    /** {@code LOW} or {@code HIGH}. A stored row is never {@code NONE}: nothing to say is no row. */
    @Column(name = "relevance", length = 10, nullable = false)
    private String relevance;

    @Column(name = "headline", length = 200, nullable = false)
    private String headline;

    /** The validated {@code SlackDigestContent} as JSON. A paraphrase; never message text. */
    @Column(name = "digest_json", columnDefinition = "TEXT", nullable = false)
    private String digestJson;

    /** Deep link to the first cited message, thread-aware. One Slack call per row. */
    @Column(name = "permalink", length = 500)
    private String permalink;

    @Column(name = "model", length = 60)
    private String model;

    @Column(name = "prompt_version", length = 60)
    private String promptVersion;

    @Column(name = "dismissed_by", length = 36)
    private String dismissedBy;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;
}
