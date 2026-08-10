package dk.trustworks.intranet.recruitmentservice.airtable;

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

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Per-Airtable-record import ledger row (ATS P21) — the idempotency
 * mechanism. The Airtable record id is the primary key: a re-run (after a
 * partial failure, or a final import after a rehearsal import) skips every
 * record that already has a row, so no candidate is ever created twice.
 * SKIPPED rows carry their reason — the reconciliation contract is
 * "100% mapped or explicitly listed as skipped-with-reason" (plan §P21 DoD).
 * <p>
 * The {@code candidate_uuid} column doubles as the retention-triage join:
 * the DPO queue's migration section finds migrated candidates through this
 * ledger, not through event-payload scans.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_airtable_records")
public class AirtableImportRecord extends PanacheEntityBase {

    public enum Status {IMPORTED, SKIPPED}

    /** Airtable {@code rec...} id. */
    @Id
    @Column(name = "airtable_record_id", length = 30, nullable = false, updatable = false)
    private String airtableRecordId;

    /** Source table (team pipeline) in the Airtable base. */
    @Column(name = "airtable_table", length = 200, nullable = false)
    private String airtableTable;

    /** FK to {@code recruitment_airtable_import_runs.uuid}. */
    @Column(name = "run_uuid", length = 36, nullable = false)
    private String runUuid;

    @Column(name = "candidate_uuid", length = 36)
    private String candidateUuid;

    @Column(name = "application_uuid", length = 36)
    private String applicationUuid;

    @Column(name = "position_uuid", length = 36)
    private String positionUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Status status;

    @Column(name = "skip_reason", length = 200)
    private String skipReason;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @PrePersist
    protected void onCreate() {
        if (importedAt == null) {
            importedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
