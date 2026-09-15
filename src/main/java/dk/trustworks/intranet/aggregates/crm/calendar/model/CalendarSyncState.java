package dk.trustworks.intranet.aggregates.crm.calendar.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One mailbox's reconciliation fence and durable health. Contains no event or error text. */
@Entity
@Table(name = "calendar_sync_state")
@Getter
@Setter
@NoArgsConstructor
public class CalendarSyncState {
    @Id
    @Column(name = "user_uuid", length = 36)
    private String userUuid;

    @Column(nullable = false)
    private long generation;

    @Column(name = "recovery_version", nullable = false)
    private int recoveryVersion;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_completed_at")
    private LocalDateTime lastCompletedAt;

    @Column(name = "last_successful_at")
    private LocalDateTime lastSuccessfulAt;

    @Column(name = "last_full_successful_at")
    private LocalDateTime lastFullSuccessfulAt;

    @Column(length = 24, nullable = false)
    private String outcome = "NEVER_SYNCED";

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "meetings_kept", nullable = false)
    private int meetingsKept;

    @Column(name = "attendees_kept", nullable = false)
    private int attendeesKept;

    @Column(name = "stale_removed", nullable = false)
    private int staleRemoved;
}
