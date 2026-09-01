package dk.trustworks.intranet.agreementservice.model;

import dk.trustworks.intranet.agreementservice.model.enums.BackfillRunStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One AI-backfill corpus walk (template-clauses spec §4.8/§10): the
 * admin-triggered, single-flight job that enumerates active employees'
 * SharePoint folders <b>by files</b> (never folder aggregates — the
 * 84-empty-folders lesson) and creates {@link AgreementBackfillItem}
 * rows for each new PDF. Counters are updated live so the console can
 * show progress while the walk runs.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "agreement_backfill_runs")
public class AgreementBackfillRun extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    /** {@link BackfillRunStatus} name. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Enumerate + count only; no downloads, no AI calls, no items. */
    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "started_by", length = 36)
    private String startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "corpus_summary", length = 500)
    private String corpusSummary;

    @Column(name = "employees_total", nullable = false)
    private int employeesTotal;

    @Column(name = "folders_total", nullable = false)
    private int foldersTotal;

    @Column(name = "folders_walked", nullable = false)
    private int foldersWalked;

    @Column(name = "files_seen", nullable = false)
    private int filesSeen;

    @Column(name = "files_skipped", nullable = false)
    private int filesSkipped;

    @Column(name = "documents_new", nullable = false)
    private int documentsNew;

    @Column(name = "proposals_created", nullable = false)
    private int proposalsCreated;

    @Column(name = "errors_count", nullable = false)
    private int errorsCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = BackfillRunStatus.RUNNING.name();
        }
    }

    public static List<AgreementBackfillRun> findRecent(int limit) {
        return find("ORDER BY startedAt DESC").page(0, limit).list();
    }

    public static List<AgreementBackfillRun> findRunning() {
        return list("status", BackfillRunStatus.RUNNING.name());
    }
}
