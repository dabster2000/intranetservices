package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.recruitmentservice.model.enums.CalendarHoldOwnerKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.CalendarHoldStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One attendee-less {@code [HOLD]} calendar event protecting one slot in
 * one calendar (D5: N events, one per interviewer plus one for the
 * room). The row's uuid doubles as the Graph {@code transactionId}, so a
 * retried create never double-books (plan §9.3).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_calendar_hold")
public class RecruitmentCalendarHold extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "slot_uuid", length = 36, nullable = false, updatable = false)
    private String slotUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_kind", length = 4, nullable = false, updatable = false)
    private CalendarHoldOwnerKind ownerKind;

    /** Soft FK users.uuid for USER holds; NULL for ROOM. */
    @Column(name = "user_uuid", length = 36, updatable = false)
    private String userUuid;

    /** The calendar the hold event lives in. */
    @Column(name = "mailbox", length = 255, nullable = false, updatable = false)
    private String mailbox;

    /** NULL until the outbox CREATE_HOLD action succeeded. */
    @Column(name = "graph_event_id", length = 255)
    private String graphEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private CalendarHoldStatus status = CalendarHoldStatus.CREATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Last time the reconciliation sweep confirmed the event exists. */
    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }
}
