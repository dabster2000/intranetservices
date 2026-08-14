package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One required interviewer's answer to one proposed slot (plan §8.1).
 * The Slack proposal card's channel/ts live here so approve/decline can
 * {@code chat.update} the card in place — the interviewer sees ✓/✗
 * immediately.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_slot_approval")
public class RecruitmentSlotApproval extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "slot_uuid", length = 36, nullable = false, updatable = false)
    private String slotUuid;

    /** Soft FK users.uuid — the required interviewer. */
    @Column(name = "user_uuid", length = 36, nullable = false, updatable = false)
    private String userUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private SlotApprovalStatus status = SlotApprovalStatus.PENDING;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /** The DM channel the proposal card was posted to. */
    @Column(name = "slack_channel_id", length = 30)
    private String slackChannelId;

    /** The proposal card's message ts — the chat.update target. */
    @Column(name = "slack_message_ts", length = 30)
    private String slackMessageTs;

    /** Silence reminders sent (defaults §29.16: 24 h, 72 h, escalate). */
    @Column(name = "nudge_count", nullable = false)
    private int nudgeCount;

    @Column(name = "last_nudged_at")
    private LocalDateTime lastNudgedAt;

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
