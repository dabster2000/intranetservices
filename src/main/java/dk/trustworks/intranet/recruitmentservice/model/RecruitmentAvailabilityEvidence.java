package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceSourceType;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One interpreted availability submission from one interviewer
 * (plan §12.1, spec §8.4): a Slack message, a calendar image (Phase 13)
 * or a recruiter's manual entry, normalized into
 * {@link RecruitmentAvailabilityConstraint} rows. Born PENDING; only
 * the interviewer's Bekræft — or an unambiguous statement the
 * extraction marked {@code requiresConfirmation=false} — promotes it
 * to CONFIRMED scheduling input (D9).
 * <p>
 * PII discipline: the source free text NEVER lands here — it lives in
 * the {@code AVAILABILITY_EVIDENCE_RECEIVED} event's {@code pii} block
 * and the extraction call, nowhere else (plan §12.2). This row is
 * structure: refs, ranges, statuses.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_availability_evidence")
public class RecruitmentAvailabilityEvidence extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "request_uuid", length = 36, nullable = false, updatable = false)
    private String requestUuid;

    /** Soft FK users.uuid — the interviewer whose availability this is. */
    @Column(name = "user_uuid", length = 36, nullable = false, updatable = false)
    private String userUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 10, nullable = false, updatable = false)
    private EvidenceSourceType sourceType;

    /** The allowlisted extraction intent (spec §13.3); UNKNOWN rows are
     * the Phase 14 manual-review surface. */
    @Column(name = "intent", length = 40, nullable = false, updatable = false)
    private String intent;

    /** Source ref: the DM channel the message arrived in. */
    @Column(name = "slack_channel_id", length = 30, updatable = false)
    private String slackChannelId;

    /** Source ref: the source message's Slack ts. */
    @Column(name = "slack_message_ts", length = 30, updatable = false)
    private String slackMessageTs;

    /** IMAGE only: SHA-256 of the original file (D10 — provable after deletion). */
    @Column(name = "file_sha256", length = 64, updatable = false)
    private String fileSha256;

    /** IMAGE only: when the S3 original was deleted (D10 audit). */
    @Column(name = "s3_deleted_at")
    private LocalDateTime s3DeletedAt;

    @Column(name = "covered_from")
    private LocalDate coveredFrom;

    /** Constraints apply INSIDE [coveredFrom, coveredTo] only (spec §11.5). */
    @Column(name = "covered_to")
    private LocalDate coveredTo;

    /**
     * IMAGE only (v2): comma-separated ISO dates the vision pass could not read
     * and that were DISCARDED rather than guessed. Lets the card tell "read as
     * free" apart from "could not read" — the distinction that made a
     * fabricated busy day indistinguishable from an omitted one in v1.
     */
    @Column(name = "unreadable_days", length = 255)
    private String unreadableDays;

    /**
     * IMAGE only (v2): comma-separated deterministic trust codes from
     * {@code AvailabilityImageReading.assess} plus NOT_CORROBORATED. Audit and
     * card disclosure only — it never blocks (the path stays frictionless).
     */
    @Column(name = "read_trust", length = 160)
    private String readTrust;

    @Column(name = "timezone", length = 50, nullable = false)
    private String timezone = "Europe/Copenhagen";

    /** da|en — the D6 loop replies in the interviewer's language. */
    @Column(name = "language", length = 2, nullable = false)
    private String language = "da";

    /** Lowest per-constraint extraction confidence; NULL for RECRUITER rows. */
    @Column(name = "confidence", precision = 3, scale = 2)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_status", length = 10, nullable = false)
    private EvidenceConfirmationStatus confirmationStatus = EvidenceConfirmationStatus.PENDING;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** End of the covered period (spec §23) — the engine ignores expired evidence. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** The older evidence row this one replaces (Ret flow / newer statement). */
    @Column(name = "supersedes_uuid", length = 36, updatable = false)
    private String supersedesUuid;

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
