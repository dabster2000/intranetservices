package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Working draft of a generated document for a candidate, scoped to a single
 * template. Represents the (candidate, template) pair: the database enforces
 * uniqueness on this pair via {@code uk_dossier_candidate_template}.
 * <p>
 * The dossier is a write-aggregate: the application layer mutates the live
 * placeholder values, signer configuration and appendix list freely while
 * {@link #status} is {@link DossierStatus#OPEN}. Each Send action freezes
 * those three pieces of state into a {@link CandidateDossierRevision}
 * snapshot through {@link #allocateRevision(RevisionKind, UUID)}.
 * <p>
 * The three JSON columns are persisted as raw JSON strings — Hibernate's
 * {@link SqlTypes#JSON} JDBC type handles the {@code JSON} column type
 * natively. The application service layer is responsible for marshalling
 * the typed value-objects (e.g. {@code SignersConfigSnapshot}) to/from
 * these strings.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "candidate_dossiers")
public class CandidateDossier extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** Internal FK to {@code recruitment_candidates.uuid}. Same aggregate cluster. */
    @Column(name = "candidate_uuid", length = 36, nullable = false)
    private String candidateUuid;

    /** Soft FK to {@code document_templates.uuid}. */
    @Column(name = "template_uuid", length = 36, nullable = false)
    private String templateUuid;

    /** JSON: {@code Map<String, String>} of placeholder values currently entered on the draft. Nullable. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "placeholder_values_json", columnDefinition = "JSON")
    private String placeholderValuesJson;

    /** JSON: ordered array of {@code SignerConfig} entries currently configured on the draft. Nullable. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signers_config_json", columnDefinition = "JSON")
    private String signersConfigJson;

    /** JSON: ordered array of {@code AppendixRef} entries (UUID + displayOrder). Nullable. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "appendices_json", columnDefinition = "JSON")
    private String appendicesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DossierStatus status = DossierStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ---- Lifecycle callbacks --------------------------------------------------

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = DossierStatus.OPEN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ---- Domain methods (rich aggregate, not anemic) -------------------------

    /**
     * Allocate the next-numbered {@link CandidateDossierRevision} on this
     * dossier and return it (unsaved). The caller must persist the returned
     * revision in the same transaction so that the
     * {@code uk_revision_dossier_version} unique constraint reliably catches
     * concurrent allocations and forces a retry.
     * <p>
     * The revision's snapshot fields are intentionally left null on the
     * returned instance — the application service is responsible for
     * populating them from the dossier's current draft state and the
     * outbound Send context (recipient, sender, note, signing case key).
     *
     * @param kind  the kind of Send action being performed
     * @param actor the user performing the Send (currently used only for
     *              guard validation; recorded by the application layer)
     * @return a new, unsaved revision wired to this dossier with
     *         {@code versionNumber} set to {@code max(existing) + 1}
     * @throws BusinessRuleViolation if the dossier is not OPEN
     */
    public CandidateDossierRevision allocateRevision(RevisionKind kind, UUID actor) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (status != DossierStatus.OPEN) {
            throw new BusinessRuleViolation(
                    "Cannot allocate revision on dossier %s: status is CLOSED"
                            .formatted(uuid));
        }
        int nextVersion = nextVersionNumber();
        CandidateDossierRevision revision = new CandidateDossierRevision();
        revision.setDossierUuid(this.uuid);
        revision.setVersionNumber(nextVersion);
        revision.setKind(kind);
        return revision;
    }

    /**
     * Close this dossier idempotently. Invoked by the application layer when
     * the parent candidate enters a terminal state. Calling on an already
     * closed dossier is a no-op (this matches the recruitment workflow's
     * "close all dossiers when the candidate is closed" cascade and is
     * intentionally not an error).
     */
    public void closeOnTerminal() {
        this.status = DossierStatus.CLOSED;
    }

    /**
     * @return the next monotonically-increasing version number for a new
     *         revision on this dossier. Computed via
     *         {@code SELECT COALESCE(MAX(versionNumber), 0) + 1} so that
     *         concurrent Send actions race on the unique constraint rather
     *         than read a stale in-memory collection.
     */
    int nextVersionNumber() {
        Integer max = getEntityManager()
                .createQuery(
                        "SELECT COALESCE(MAX(r.versionNumber), 0) " +
                        "FROM CandidateDossierRevision r " +
                        "WHERE r.dossierUuid = :dossierUuid",
                        Integer.class)
                .setParameter("dossierUuid", uuid)
                .getSingleResult();
        return (max == null ? 0 : max) + 1;
    }
}
