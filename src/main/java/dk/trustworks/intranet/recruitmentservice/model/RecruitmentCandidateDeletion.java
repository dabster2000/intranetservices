package dk.trustworks.intranet.recruitmentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The audit ledger of ADMIN candidate hard deletes (V515) — and, after the
 * delete has run, the <em>only</em> surviving record that the candidate ever
 * existed.
 *
 * <p>The hard delete removes the candidate's {@code recruitment_events} rows
 * along with everything else, and those events are where every other
 * recruitment action records itself (including the anonymizer's own
 * {@code CANDIDATE_ANONYMIZED} bookkeeping). So the delete would otherwise
 * erase its own audit trail. This row does not, because it carries
 * <b>no foreign key</b> to the candidate — the
 * {@code EmployeeDocumentAudit(null, userUuid, …)} idiom
 * ({@code EmployeeDocumentService.eraseAllForUser}), which exists for exactly
 * the same reason.</p>
 *
 * <h3>PII contract</h3>
 * The candidate's name, email, phone and LinkedIn must never be written here:
 * storing them would defeat the deletion this row records. The only candidate
 * identifier is the (now dangling) uuid. {@code reason} is admin-written free
 * text and is treated as potentially naming the person — which is why V515
 * puts this table on the prod → staging sync exclusion list.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_candidate_deletions")
public class RecruitmentCandidateDeletion extends PanacheEntityBase {

    /** Row opened; nothing irreversible has been done yet. */
    public static final String OUTCOME_ATTEMPTED = "ATTEMPTED";
    /** Graph/Slack redaction done and recorded; the cascade has not committed. */
    public static final String OUTCOME_EXTERNAL_REDACTED = "EXTERNAL_REDACTED";
    /** The cascade committed. The only outcome that means "the candidate is gone". */
    public static final String OUTCOME_COMPLETED = "COMPLETED";
    /**
     * The cascade threw after the external redaction. The candidate STILL
     * EXISTS; {@link #externalRedaction} names what was already cancelled or
     * rewritten out there and needs a human.
     */
    public static final String OUTCOME_ROLLED_BACK = "ROLLED_BACK";

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** Soft reference to the deleted candidate. Permanently dangling by design. */
    @Column(name = "candidate_uuid", length = 36, nullable = false, updatable = false)
    private String candidateUuid;

    /** Soft FK to {@code user.uuid} — the {@code X-Requested-By} admin. */
    @Column(name = "actor_uuid", length = 36, nullable = false, updatable = false)
    private String actorUuid;

    /**
     * UTC, stamped when the row is OPENED — before any Graph or Slack call and
     * before the cascade. On a row that never reached
     * {@link #OUTCOME_COMPLETED} it is therefore "when the delete was
     * attempted", not "when the candidate went away".
     */
    @Column(name = "deleted_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME(3)")
    private LocalDateTime deletedAt;

    /** Required, non-trivial. Free text — assume it may name the candidate. */
    @Column(name = "reason", length = 1000, nullable = false, updatable = false)
    private String reason;

    /**
     * Where this delete got to. The row is opened {@link #OUTCOME_ATTEMPTED}
     * BEFORE any irreversible external work and only reaches
     * {@link #OUTCOME_COMPLETED} inside the cascade's own transaction, so an
     * operator finds every partial delete with
     * {@code WHERE outcome <> 'COMPLETED'} rather than by accident.
     */
    @Column(name = "outcome", length = 32, nullable = false)
    private String outcome = OUTCOME_ATTEMPTED;

    /**
     * JSON object: what this delete irreversibly changed OUTSIDE the database
     * before the cascade ran — Outlook events cancelled, Slack root cards and
     * discussion roots rewritten. Committed before the destructive
     * transaction opens, which is what makes a rolled-back delete
     * diagnosable. Ids and uuids only, never a name.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "external_redaction", columnDefinition = "JSON")
    private String externalRedaction;

    /**
     * JSON object: table name → rows deleted. Structural only, never PII.
     * Updatable and {@code {}} until the cascade runs: the row exists before
     * the deletes do.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deleted_counts", columnDefinition = "JSON", nullable = false)
    private String deletedCounts;

    /**
     * JSON object: what survived and is still out there (Slack cards, Graph
     * events, S3 objects, the SharePoint folder, {@code mail} rows, whether
     * the reporting projection rebuilt). Updatable, because the S3 and
     * projection legs only run after this row's transaction commits.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "residue", columnDefinition = "JSON")
    private String residue;

    @PrePersist
    void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (deletedAt == null) {
            deletedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
