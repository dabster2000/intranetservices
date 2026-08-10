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
import java.util.UUID;

/**
 * One Airtable dry-run or import invocation (ATS P21). The reconciliation
 * report is persisted here as JSON so the outcome survives deploys — a
 * deploy kills an in-flight run silently (the ECS task is replaced), so
 * status must never live in memory. A run left {@code RUNNING} after its
 * task died is detectable by {@code started_at} age; re-running is safe
 * because the per-record ledger makes the import idempotent.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_airtable_import_runs")
public class AirtableImportRun extends PanacheEntityBase {

    public enum Mode {DRY_RUN, IMPORT}

    public enum Status {
        RUNNING,
        COMPLETED,
        /** A real import refused to start: unmapped values or a running sibling. */
        BLOCKED,
        FAILED
    }

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 10, nullable = false, updatable = false)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Status status = Status.RUNNING;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** users.uuid from X-Requested-By — the admin who started the run. */
    @Column(name = "started_by", length = 36, nullable = false, updatable = false)
    private String startedBy;

    /** Reconciliation report JSON ({@code AirtableReconciliationReport}). */
    @Column(name = "report", columnDefinition = "JSON")
    private String report;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (startedAt == null) {
            startedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        if (status == null) {
            status = Status.RUNNING;
        }
    }
}
