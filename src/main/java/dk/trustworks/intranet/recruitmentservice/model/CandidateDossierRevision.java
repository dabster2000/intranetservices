package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Send-action snapshot row. Every column except {@code uuid} is declared
 * {@code updatable=false} so post-persist mutation cannot reach the database
 * — the JPA dirty-check would write the column, then Hibernate's metamodel
 * suppresses the UPDATE. Setters exist only for hydration and pre-persist
 * population in the {@code DossierRevisionService.snapshotFromValues} flow.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "candidate_dossier_revisions")
public class CandidateDossierRevision extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** Internal FK to {@code candidate_dossiers.uuid}. */
    @Column(name = "dossier_uuid", length = 36, nullable = false, updatable = false)
    private String dossierUuid;

    /** 1-based monotonic version per dossier. Allocated by {@code CandidateDossier.allocateRevision()}. */
    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false, updatable = false)
    private RevisionKind kind;

    /** Frozen JSON snapshot of placeholder values at Send time. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "placeholder_values_snapshot", columnDefinition = "JSON", nullable = false, updatable = false)
    private String placeholderValuesSnapshot;

    /** Frozen JSON snapshot of signer configuration at Send time. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signers_config_snapshot", columnDefinition = "JSON", nullable = false, updatable = false)
    private String signersConfigSnapshot;

    /** Frozen JSON snapshot of the ordered appendix list at Send time. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "appendices_snapshot", columnDefinition = "JSON", nullable = false, updatable = false)
    private String appendicesSnapshot;

    /** Soft FK to {@code signing_cases.case_key}. Only set for {@link RevisionKind#SIGNATURE}. */
    @Column(name = "signing_case_key", length = 255, updatable = false)
    private String signingCaseKey;

    /**
     * Frozen JSON array of {@code {filename, fileUuid}} entries for the
     * generated PDFs persisted to S3 at Send time. {@code null} for legacy
     * revisions written before V321. Immutable: same {@code updatable=false}
     * treatment as the other snapshot columns.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generated_pdfs_snapshot", columnDefinition = "JSON", updatable = false)
    private String generatedPdfsSnapshot;

    /**
     * When the S3-stored generated PDFs may be reaped after the parent
     * candidate has been promoted. Set by the promote flow when the
     * SharePoint copy succeeds; nulled by {@code S3RetentionCleanupBatchlet}
     * after the S3 deletes succeed. Mutable — unlike snapshot columns,
     * lifecycle metadata may evolve post-persist.
     */
    @Column(name = "s3_retention_until")
    private LocalDateTime s3RetentionUntil;

    @Column(name = "recipient_email", length = 255, nullable = false, updatable = false)
    private String recipientEmail;

    /** Soft FK to {@code users.uuid} of the actor who performed the Send. */
    @Column(name = "sent_by_useruuid", length = 36, nullable = false, updatable = false)
    private String sentByUseruuid;

    @Column(name = "note", columnDefinition = "TEXT", updatable = false)
    private String note;

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
}
