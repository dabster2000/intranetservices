package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxStatus;
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
 * One intended external write of the Method B orchestrator (plan §8.3,
 * following the invoice-booking outbox idiom): written in the SAME
 * transaction as the state change that wants it, executed by the
 * dispatcher sweep with an atomic claim, retried with backoff and
 * dead-lettered (FAILED) after the attempt cap.
 * <p>
 * {@code payload_json} carries structural parameters only (uuids,
 * mailboxes, option numbers) — free text and candidate PII live in
 * event {@code pii} blocks, never here: outbox rows outlive their
 * execution and are not covered by the GDPR anonymizer.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_scheduling_outbox")
public class RecruitmentSchedulingOutbox extends PanacheEntityBase {

    /** {@code last_error} truncation bound, as InvoiceBookingAttempt. */
    public static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "request_uuid", length = 36, nullable = false, updatable = false)
    private String requestUuid;

    @Column(name = "slot_uuid", length = 36, updatable = false)
    private String slotUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 40, nullable = false, updatable = false)
    private SchedulingOutboxAction action;

    /** {@code request+slot+action+version} — the same intended action is
     * never enqueued twice (unique key; INSERT collisions are ignored). */
    @Column(name = "idempotency_key", length = 200, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "payload_json", columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 12, nullable = false)
    private SchedulingOutboxStatus status = SchedulingOutboxStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** Due time; exponential backoff pushes it out on retry. */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    /** When an instance atomically claimed the row. Stale claims are
     * re-eligible after the dispatcher's claim timeout. */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public void setLastError(String error) {
        this.lastError = (error != null && error.length() > MAX_ERROR_LENGTH)
                ? error.substring(0, MAX_ERROR_LENGTH)
                : error;
    }

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
