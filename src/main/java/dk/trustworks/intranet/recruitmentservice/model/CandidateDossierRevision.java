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
 * Immutable Send-action snapshot. Persisting one of these rows is the only
 * outwardly-visible side effect of a Send: it captures exactly what was
 * dispatched (placeholder values, signer config, appendix list) so the
 * dossier timeline can be reconstructed forever after.
 * <p>
 * The three {@code _snapshot} columns store frozen JSON copies of the
 * dossier draft state at allocation time. They are typed as raw JSON
 * strings on the entity and the application service is responsible for
 * (de)serialising the typed value-objects from
 * {@code dk.trustworks.intranet.recruitmentservice.model.snapshot}.
 * <p>
 * Immutability of snapshots is application-enforced: once persisted, no
 * code path should mutate {@link #placeholderValuesSnapshot},
 * {@link #signersConfigSnapshot} or {@link #appendicesSnapshot}. The
 * Lombok-generated setters exist only so JPA can hydrate the row on read;
 * callers must not invoke them outside aggregate construction.
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
