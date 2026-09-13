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
 * from {@code (graphEventId, userUuid)} so a re-sync updates the row instead of adding one.
 *
 * <p>Third-party personal data lives in {@link AccountMeetingAttendee}. The agreed
 * retention is 24 months after the account's last activity — the purge job is NOT built.
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

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;
}
