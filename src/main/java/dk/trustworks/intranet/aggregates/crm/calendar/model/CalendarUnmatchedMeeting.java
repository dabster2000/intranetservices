package dk.trustworks.intranet.aggregates.crm.calendar.model;

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
 * One sighting of an unattributed meeting domain: which mailbox, which day (V601).
 *
 * <p><b>This exists so the counts on {@link CalendarUnmatchedDomain} can be right.</b> The
 * calendar sync re-reads the last {@code INCREMENTAL_BACK_DAYS} on every run, so a counter
 * that were merely incremented would count the same coffee fourteen times and report
 * "3 meetings since June" as 42. The uuid is deterministic in (graph event, mailbox,
 * domain), so a re-sync overwrites its own row and the aggregate is recomputed from the
 * ledger rather than accumulated.
 *
 * <p>No attendee, no display name, no subject — the same refusal as everywhere else in
 * this lane. The mailbox owner is here because "3 meetings since June · Tommy, Lukas" is
 * what makes the suggestion worth acting on, and because it is what {@code people_count}
 * counts.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "calendar_unmatched_meeting")
public class CalendarUnmatchedMeeting extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "domain", length = 190, nullable = false)
    private String domain;

    /** The Trustworks mailbox the meeting was read from. */
    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;
}
