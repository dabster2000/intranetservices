package dk.trustworks.intranet.aggregates.crm.slack.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A general Slack channel an admin asked the CRM to read for client news (spec §5.1).
 *
 * <p>The account-space lane knows which client a channel is about before it reads a word —
 * {@code client_account.slack_space} says so. A channel like {@code #ledelse} is about
 * nothing in particular, so the only thing that can be configured about it is that it is
 * worth reading at all; which accounts it turned out to mention is decided per day and
 * lands on {@link AccountSlackMention}. That is the whole difference between the two lanes.
 *
 * <p><b>The cursor lives here and not on the produced rows.</b> A complete day may
 * correctly produce no mention at all, so a lane that resumed from "the newest row written"
 * would re-read that day for ever. {@link #cursorTs} is the Slack {@code ts} of the last
 * message of the last complete day read, and it is advanced in the same transaction as that
 * day's rows, so a run killed half way resumes at the first day it did not finish.
 *
 * <p>{@link #linkError} carries the verdict of the last attempt to read the channel and is
 * cleared on every successful read. A channel the bot cannot get into is still SAVED with
 * its verdict rather than refused, because the fix — inviting the bot — happens in Slack
 * and not here, and the nightly run re-checks it without anybody having to come back.
 *
 * <p>{@link #enabled} pauses a channel and KEEPS its cursor, so switching it back on
 * continues where it stopped instead of re-reading months. Deleting the row leaves every
 * mention it produced in place: history was true when it was written.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "crm_slack_source_channel")
public class SlackSourceChannel extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /** Resolved from whatever was typed — an id or a {@code #name} — when the row was saved. */
    @Column(name = "channel_id", length = 32, nullable = false)
    private String channelId;

    /** As Slack spells it, without the {@code #}; refreshed on every successful read. */
    @Column(name = "channel_name", length = 80, nullable = false)
    private String channelName;

    /**
     * The house idiom drops the {@code is_} prefix from the field name ({@code is_visible}
     * ⇒ {@code visible}), which here would land on a Java keyword — hence the suffix.
     */
    @Column(name = "is_private", nullable = false)
    private boolean privateChannel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** {@code NOT_FOUND}, {@code NOT_IN_CHANNEL} or {@code ARCHIVED}; null when healthy. */
    @Column(name = "link_error", length = 40)
    private String linkError;

    /** Slack {@code ts} of the last message of the last complete day read. Null until the first run. */
    @Column(name = "cursor_ts", length = 32)
    private String cursorTs;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;
}
