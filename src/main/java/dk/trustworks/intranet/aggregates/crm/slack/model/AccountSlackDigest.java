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
 * One Copenhagen calendar day of a linked account Slack space (CRM spec §3.2, §4.9), as
 * the timeline shows it.
 *
 * <p><b>There is no message text field, and that is deliberate.</b> V594 mirrors V588's
 * "no subject column": what colleagues wrote in the channel names third parties and
 * quotes documents, and it is held in memory for the length of one model call and then
 * gone. What is stored is the model's STRUCTURED reading after
 * {@code AccountSlackDigestService} has re-validated, capped and stripped it —
 * {@link #headline} for the row, {@link #digestJson} for the decisions, next steps, risks,
 * client asks, client-side people and topics underneath it. A future change that adds a
 * text column here is undoing a decision, not filling a gap.
 *
 * <p><b>One row per (client, day).</b> {@link #uuid} is derived deterministically from the
 * pair so a re-sync updates rather than duplicates, and the feed gets one line per day
 * instead of one per message.
 *
 * <p>{@link #headline} null with a non-null row means the model was off, failed, or read
 * the day as irrelevant chatter; the feed then composes a counts line at read time
 * ("#a_oersted: 14 messages (Mikkel, Jonas)"), so a day with activity is never silently
 * absent.
 *
 * <p>Who from Trustworks was talking lives in {@link AccountSlackDigestParticipant}. The
 * agreed retention is 24 months after the account's last activity — the purge job is NOT
 * built, and this table must be on its list when it is.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_slack_digest")
public class AccountSlackDigest extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    @Column(name = "channel_id", length = 32, nullable = false)
    private String channelId;

    /** Denormalised: a channel rename must not rewrite what the timeline said last month. */
    @Column(name = "channel_name", length = 80, nullable = false)
    private String channelName;

    @Column(name = "digest_date", nullable = false)
    private LocalDate digestDate;

    /** Human top-level messages that day. Bot posts and joins/leaves are never counted. */
    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "thread_reply_count", nullable = false)
    private int threadReplyCount;

    /** The incremental cursor: the newest Slack {@code ts} read into this row. Never rendered. */
    @Column(name = "last_message_ts", length = 32, nullable = false)
    private String lastMessageTs;

    @Column(name = "permalink", length = 500)
    private String permalink;

    /**
     * The KIND of account event, from {@code SlackDigestContent}'s closed set — the field
     * {@code relevance} is derived from, and the one a cross-account feed filters on
     * ("every EXTENSION signal this quarter"). A varchar and not an enum: an unknown
     * value from a future prompt version degrades to NONE on read rather than failing
     * the write of a row we already paid a model call for.
     */
    @Column(name = "signal_type", length = 20)
    private String signalType;

    /** {@code NONE}, {@code LOW} or {@code HIGH} — DERIVED from {@link #signalType}; null when the model did not run. */
    @Column(name = "relevance", length = 8)
    private String relevance;

    @Column(name = "headline", length = 200)
    private String headline;

    /** The validated {@code SlackDigestContent} as JSON. A paraphrase; never message text. */
    @Column(name = "digest_json", columnDefinition = "TEXT")
    private String digestJson;

    @Column(name = "model", length = 60)
    private String model;

    @Column(name = "prompt_version", length = 60)
    private String promptVersion;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;
}
