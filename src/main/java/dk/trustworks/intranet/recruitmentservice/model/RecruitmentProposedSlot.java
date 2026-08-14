package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One concrete interview time under consideration for one Method B
 * scheduling request (plan §8.1). System-written — created by the
 * advance sweep, moved by interviewer answers, rechecks, holds and the
 * candidate's selection.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_proposed_slot")
public class RecruitmentProposedSlot extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "request_uuid", length = 36, nullable = false, updatable = false)
    private String requestUuid;

    /** Per-request sequence (1,2,3,…) — the "mulighed i/n" label in
     * hold subjects (D12: the label, never the candidate name). */
    @Column(name = "option_no", nullable = false, updatable = false)
    private int optionNo;

    /** Wall-clock Europe/Copenhagen, as everywhere in the interview loop. */
    @Column(name = "slot_start", nullable = false, updatable = false)
    private LocalDateTime slotStart;

    @Column(name = "slot_end", nullable = false, updatable = false)
    private LocalDateTime slotEnd;

    /** The bookable room secured for this slot; NULL = no room. */
    @Column(name = "room_email", length = 255)
    private String roomEmail;

    @Column(name = "room_name", length = 200)
    private String roomName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ProposedSlotStatus status = ProposedSlotStatus.DISCOVERED;

    /** Structural rejection cause: INTERVIEWER_DECLINED |
     * RECHECK_CONFLICT | HOLD_FAILURE | HOLD_LOST | RECRUITER_RELEASED … */
    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    /** Candidate deadline + 1 h buffer once offered — the cleanup sweep
     * releases past-due slots (plan §9.4). */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
