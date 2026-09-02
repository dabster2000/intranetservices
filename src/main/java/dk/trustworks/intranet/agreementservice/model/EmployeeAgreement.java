package dk.trustworks.intranet.agreementservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The agreement registry (template-clauses spec §4.7): one row per
 * negotiated term per person. Subject is a user XOR a candidate
 * (DB CHECK {@code chk_ea_subject}); candidate rows re-key to the user
 * inside the HIRED conversion transaction.
 *
 * <p>Registry data is salary-adjacent: rows are served exclusively by
 * {@code AgreementResource} under the {@code agreements:*} scopes and
 * are never serialized on User responses.</p>
 *
 * <p>{@code amount}/{@code valid_from}/{@code valid_to}/{@code effective_date}
 * are first-class columns because MariaDB stores JSON as LONGTEXT —
 * queries never reach into {@code parameters_json}.</p>
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "employee_agreements")
public class EmployeeAgreement extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    /** XOR with {@link #candidateUuid}. */
    @Column(name = "user_uuid", length = 36)
    private String userUuid;

    @Column(name = "candidate_uuid", length = 36)
    private String candidateUuid;

    /** FK → {@code agreement_types.type_key}. */
    @Column(name = "agreement_type", nullable = false, length = 50)
    private String agreementType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    /** Drives the ACTIVE → EXPIRED sweep and the 60/14-day alerts. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /** Everything not mapped to a first-class column. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters_json", columnDefinition = "JSON")
    private String parametersJson;

    /** NULL for backfill/manual/Individuel aftale. */
    @Column(name = "clause_uuid", length = 36)
    private String clauseUuid;

    /** Exactly which wording was signed (D7). */
    @Column(name = "clause_version_uuid", length = 36)
    private String clauseVersionUuid;

    /** {@code AgreementSource} name: SIGNED_CASE / BACKFILL / MANUAL. */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /** Idempotency key half for SIGNED_CASE rows. */
    @Column(name = "signing_case_key", length = 255)
    private String signingCaseKey;

    /** Optional link to the signed PDF; set by manual entry (backfilled rows link through the employee document). */
    @Column(name = "document_url", length = 1000)
    private String documentUrl;

    /** {@code AgreementStatus} name: ACTIVE / EXPIRED / SUPERSEDED / TERMINATED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "notified_60d_at")
    private LocalDateTime notified60dAt;

    @Column(name = "notified_14d_at")
    private LocalDateTime notified14dAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "modified_by", length = 36)
    private String modifiedBy;

    /** Set on backfill review (Phase 4). */
    @Column(name = "confirmed_by", length = 36)
    private String confirmedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        // updated_at is DB-maintained (ON UPDATE CURRENT_TIMESTAMP);
        // nothing to stamp here — modified_by is set by the service.
    }

    // --- Panache finder methods ---

    public static List<EmployeeAgreement> findByUser(String userUuid) {
        return list("userUuid = ?1 ORDER BY createdAt DESC", userUuid);
    }

    public static List<EmployeeAgreement> findByCandidate(String candidateUuid) {
        return list("candidateUuid = ?1 ORDER BY createdAt DESC", candidateUuid);
    }

    public static boolean existsForCaseClause(String signingCaseKey, String clauseUuid) {
        return count("signingCaseKey = ?1 AND clauseUuid = ?2", signingCaseKey, clauseUuid) > 0;
    }

    public static boolean existsForCaseCustom(String signingCaseKey, String customTitle) {
        return count("signingCaseKey = ?1 AND clauseUuid IS NULL AND title = ?2", signingCaseKey, customTitle) > 0;
    }

    /**
     * Candidate → user re-key, called inside the HIRED conversion
     * transaction (spec §8). One UPDATE keeps the XOR CHECK satisfied.
     */
    public static long rekeyCandidateToUser(String candidateUuid, String userUuid) {
        return update("userUuid = ?1, candidateUuid = null WHERE candidateUuid = ?2", userUuid, candidateUuid);
    }
}
