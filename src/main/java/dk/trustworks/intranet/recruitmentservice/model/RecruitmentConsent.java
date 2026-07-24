package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
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
 * A dated, renewable, withdrawable consent record (ATS spec §4.1) — the
 * GDPR-grade replacement for Airtable's consent checkbox.
 * <p>
 * P4 creates {@code REQUESTED} rows when an application is returned to the
 * pool (silver medalist — pool retention needs consent, spec §4.2 terminal
 * note). Granting, withdrawing, expiry and the public
 * {@code /consent/[token]} page arrive with the P19 GDPR engine; until
 * then the recruiter/DPO handles pool consent manually (documented plan
 * §P4 limitation). {@link #tokenHash} therefore stays {@code NULL} until
 * P19 mints tokens.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_consents")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentConsent extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_candidates.uuid}. */
    @Column(name = "candidate_uuid", length = 36, nullable = false, updatable = false)
    private String candidateUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 30, nullable = false, updatable = false)
    private RecruitmentConsentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RecruitmentConsentStatus status = RecruitmentConsentStatus.REQUESTED;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    /** {@code granted_at} + 12 months — maintained from P19. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** SHA-256 of the public consent-page token (P19). Never serialized. */
    @JsonIgnore
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    /**
     * UTC moment after which the public consent-page token is refused
     * (V448). Stamped at mint — the sweep sets it to the candidate's
     * retention deadline, so a link never outlives the data it controls.
     * Never serialized (token bookkeeping is server-internal).
     */
    @JsonIgnore
    @Column(name = "token_expires_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime tokenExpiresAt;

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
        if (status == null) {
            status = RecruitmentConsentStatus.REQUESTED;
        }
    }
}
