package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecordCheckOutcome;
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
 * One ISAE 3000 criminal-record draw (V481): exactly one row per
 * candidate, created on first entry into the OFFER stage. The row is the
 * audit trail — {@code rateApplied} pins the governing rate,
 * {@code selected} the deterministic draw result, and the outcome fields
 * the human verification. The attest itself is never stored.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_record_checks")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentRecordCheck extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_candidates.uuid}; unique — one draw per candidate. */
    @Column(name = "candidate_uuid", length = 36, nullable = false, updatable = false)
    private String candidateUuid;

    /** The application whose OFFER entry triggered the draw. */
    @Column(name = "application_uuid", length = 36, updatable = false)
    private String applicationUuid;

    @Column(name = "drawn_at", nullable = false, updatable = false)
    private LocalDateTime drawnAt;

    /** Sample rate (percent) in force at draw time. */
    @Column(name = "rate_applied", nullable = false, updatable = false)
    private int rateApplied;

    /** True — the candidate must present a clean straffeattest. */
    @Column(name = "selected", nullable = false, updatable = false)
    private boolean selected;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20, nullable = false)
    private RecordCheckOutcome outcome = RecordCheckOutcome.PENDING;

    /** {@code users.uuid} of the HR user who saw the attest. */
    @Column(name = "verified_by", length = 36)
    private String verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

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
