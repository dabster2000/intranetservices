package dk.trustworks.intranet.signing.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of one clause (or free-text Individuel aftale) a
 * sent signing case contained (template-clauses spec §4.5). Written once
 * at case creation; the Phase 3 {@code AgreementRecorder} reads these
 * rows when the case reaches COMPLETED — the single source for registry
 * writes in both the wizard and the dossier flow.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "signing_case_clauses")
public class SigningCaseClause extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    /** FK → {@code signing_cases.id}. */
    @Column(name = "signing_case_id", nullable = false)
    private Long signingCaseId;

    /** NULL for a free-text Individuel aftale. */
    @Column(name = "clause_uuid", length = 36)
    private String clauseUuid;

    /** Exactly which wording was sent (D7). NULL for Individuel aftale. */
    @Column(name = "clause_version_uuid", length = 36)
    private String clauseVersionUuid;

    /** As rendered — an INLINE clause may have fallen back to ADDENDUM. */
    @Column(name = "render_mode", nullable = false, length = 20)
    private String renderMode;

    /** JSON map of the clause's placeholder values. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameter_values_json", columnDefinition = "JSON")
    private String parameterValuesJson;

    @Column(name = "custom_title", length = 255)
    private String customTitle;

    @Column(name = "custom_text", columnDefinition = "TEXT")
    private String customText;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // --- Panache finder methods ---

    public static List<SigningCaseClause> findByCase(Long signingCaseId) {
        return list("signingCaseId = ?1 ORDER BY displayOrder", signingCaseId);
    }

    /** Does any sent case reference this clause version? Drives immutability (D7). */
    public static boolean versionInUse(String clauseVersionUuid) {
        return count("clauseVersionUuid = ?1", clauseVersionUuid) > 0;
    }

    public static boolean clauseInUse(String clauseUuid) {
        return count("clauseUuid = ?1", clauseUuid) > 0;
    }

    public static long countByClause(String clauseUuid) {
        return count("clauseUuid = ?1", clauseUuid);
    }
}
