package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailStatus;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * One review-before-send queue row (ATS plan §P15): a rendered candidate
 * email awaiting recruiter approval. Holds the RENDERED subject/body and
 * the recipient snapshot at queue time — the template may be edited or
 * deactivated later without affecting queued rows.
 * <p>
 * Personal data lives in {@link #toEmail}/{@link #subject}/{@link #body} —
 * P19's anonymizer must scrub or dismiss a candidate's PENDING rows
 * (carry-over recorded in findings §P15).
 * <p>
 * State changes only through {@code RecruitmentEmailService} (one-shot
 * approve/dismiss under a pessimistic row lock).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_pending_emails")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentPendingEmail extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_candidates.uuid}. */
    @Column(name = "candidate_uuid", length = 36, nullable = false, updatable = false)
    private String candidateUuid;

    /** Soft FK to {@code recruitment_applications.uuid}; null when the trigger had no application. */
    @Column(name = "application_uuid", length = 36, updatable = false)
    private String applicationUuid;

    /** Soft FK to the template at queue time (snapshot semantics — may be stale). */
    @Column(name = "template_uuid", length = 36, updatable = false)
    private String templateUuid;

    @Column(name = "template_key", length = 60, nullable = false, updatable = false)
    private String templateKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 60, nullable = false, updatable = false)
    private RecruitmentPendingEmailReason reason;

    /** Recipient snapshot (personal data — P19 scrubs PENDING rows). */
    @Column(name = "to_email", length = 255, nullable = false)
    private String toEmail;

    /** Rendered subject snapshot (personal data). */
    @Column(name = "subject", length = 300, nullable = false)
    private String subject;

    /** Rendered plain-text body snapshot (personal data). */
    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 12, nullable = false)
    private RecruitmentPendingEmailStatus status = RecruitmentPendingEmailStatus.PENDING;

    /** {@code recruitment_events.event_id} that queued this row (provenance + duplicate guard). */
    @Column(name = "trigger_event_uuid", length = 36, updatable = false)
    private String triggerEventUuid;

    @Column(name = "resolved_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime resolvedAt;

    /** Soft FK to {@code users.uuid} — who approved/dismissed. */
    @Column(name = "resolved_by", length = 36)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String resolvedBy;

    // ---- Audit columns (house Auditable pattern) ---------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }
}
