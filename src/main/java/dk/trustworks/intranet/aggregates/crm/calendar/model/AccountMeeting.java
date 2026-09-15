package dk.trustworks.intranet.aggregates.crm.calendar.model;

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
 * One meeting with a client, as seen from one consenting Trustworks mailbox
 * (CRM spec §3.2, §3.7).
 *
 * <p><b>There is no subject field, and that is deliberate.</b> The spec permits attendees,
 * date and duration and forbids the subject; leaving the column out makes that structural
 * instead of something a future change might quietly undo. {@code AccountCalendarSyncService}
 * likewise never puts {@code subject} or {@code body} in its Graph {@code $select}, so the
 * text never leaves Microsoft's tenant. The account feed describes a meeting by who was in
 * it.
 *
 * <p><b>One row per mailbox, not per meeting.</b> A meeting with two consenting Trustworks
 * attendees produces two rows, which is exactly what the relationship graph needs: both of
 * them were there and both now have an edge. {@link #uuid} is derived deterministically
 * from {@code (graphEventId, userUuid, clientUuid)} so each matched account has its own
 * projection and a re-sync updates the row instead of adding one.
 * {@link #icalUid} is what says those two rows are ONE meeting: it is Graph's identifier for
 * the event across calendars, and the account timeline folds rows that share it into a single
 * line naming everybody of ours who was there.
 *
 * <p><b>Only meetings that have ended.</b> The sync's window closes at the moment the run
 * starts and an event still running at that moment is left for the next night, so
 * {@link #occurredAt} is never in the future. A row that no longer matches what the mailbox
 * says — cancelled, deleted, declined, moved, or re-judged by a rule — is removed by the next
 * complete read of the window it sits in.
 *
 * <p>Third-party personal data lives in {@link AccountMeetingAttendee}. The agreed
 * retention is 24 months after the account's last activity, enforced by
 * {@code CrmRetentionPurgeJob} (V608), which ships disarmed.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_meeting")
public class AccountMeeting extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /** Resolved from the attendees' e-mail domains via {@code client_domain}. */
    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** The Trustworks mailbox this row was read from. */
    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    @Column(name = "graph_event_id", length = 600, nullable = false)
    private String graphEventId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /** Everyone invited, internal and external — the size of the room, nothing more. */
    @Column(name = "attendee_count", nullable = false)
    private int attendeeCount;

    /**
     * How many of those were on our own tenant (spec §4.2, V607).
     *
     * <p>The number that separates a meeting with a client from our own all-hands with one
     * client address on the invitation. At or above the configured threshold — eight by
     * default — the event is dropped as internal and never reaches this table at all, so
     * every stored row is below it. The count is kept anyway because the drop is a
     * judgement and a judgement needs evidence: it is what lets somebody ask afterwards why
     * a meeting they remember is not on the tab, and what lets the threshold be re-tuned
     * against real data instead of a guess.
     *
     * <p>A bare integer, and deliberately so — no attendee, no name, no address. Rows
     * written before V607 read 0, which means "not known" rather than "none of us was
     * there"; every meeting has at least the mailbox owner in it.
     */
    @Column(name = "own_attendee_count", nullable = false)
    private int ownAttendeeCount;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;

    /** Membership of a completed mailbox read. Never compared using timestamp precision. */
    @Column(name = "sync_generation", nullable = false)
    private long syncGeneration;

    @Column(name = "series_master_id", length = 600)
    private String seriesMasterId;

    @Column(name = "recurring", nullable = false)
    private boolean recurring;

    /** NORMAL or STARRED_OVERRIDE; no meeting content is retained. */
    @Column(name = "inclusion_reason", length = 32, nullable = false)
    private String inclusionReason = "NORMAL";

    /**
     * Graph's {@code iCalUId}: the same for one meeting in every attendee's mailbox, and
     * different for every occurrence of a series (V614).
     *
     * <p>Nullable, and null means "identity unknown", never "no identity": every row written
     * before V614 is null until a full read of its mailbox rewrites it, and the feed renders
     * such a row on its own exactly as it always did. Never used as a key on its own — always
     * with {@code clientUuid} — because the same meeting can be attributed to two clients from
     * two mailboxes only by a rule change, and folding across accounts would hide that.
     */
    @Column(name = "ical_uid", length = 255)
    private String icalUid;
}
