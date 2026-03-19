package dk.trustworks.intranet.aggregates.bugreport.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * Scheduled job (every 5 minutes) that recovers stuck auto-fix tasks.
 *
 * <p>Three recovery scenarios:
 * <ol>
 *   <li>Stuck PROCESSING tasks: lease expired, worker crashed. Reset to PENDING
 *       (if retries remain) or FAILED.</li>
 *   <li>Orphaned AUTO_FIX_REQUESTED bug reports: task completed/failed but bug
 *       report status was never reverted.</li>
 *   <li>Force-closed bug reports: admin closed the report while a task is
 *       still processing. Cancel the task.</li>
 * </ol>
 */
@JBossLog
@ApplicationScoped
public class AutoFixTaskReaper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    BugReportService bugReportService;

    @Scheduled(every = "5m")
    @Transactional
    public void reapStaleTasks() {
        reapStuckProcessingTasks();
        reconcileOrphanedBugReports();
        cancelTasksForClosedReports();
    }

    /**
     * Scenario 1: PROCESSING tasks whose lease has expired.
     * The worker stopped heartbeating -- likely crashed.
     */
    private void reapStuckProcessingTasks() {
        @SuppressWarnings("unchecked")
        List<Object[]> staleTasks = em.createNativeQuery(
            "SELECT task_id, bug_report_uuid, retry_count, max_retries, metadata " +
            "FROM autofix_tasks " +
            "WHERE status = 'PROCESSING' " +
            "AND lease_expires_at IS NOT NULL " +
            "AND lease_expires_at < NOW()")
            .getResultList();

        for (Object[] row : staleTasks) {
            String taskId = (String) row[0];
            String bugReportUuid = (String) row[1];
            int retryCount = ((Number) row[2]).intValue();
            int maxRetries = ((Number) row[3]).intValue();
            String metadataStr = (String) row[4];

            if (retryCount < maxRetries) {
                em.createNativeQuery(
                    "UPDATE autofix_tasks SET status = 'PENDING', " +
                    "retry_count = retry_count + 1, worker_id = NULL, " +
                    "started_at = NULL, lease_expires_at = NULL, heartbeat_at = NULL " +
                    "WHERE task_id = :taskId")
                    .setParameter("taskId", taskId)
                    .executeUpdate();

                bugReportService.addComment(bugReportUuid, "system:autofix-reaper",
                    "Auto-fix task reset by reaper — worker stopped responding. Retrying. Task: " + taskId, true);

                log.infof("Reaped stale task %s -> PENDING (retry %d)", taskId, retryCount + 1);
            } else {
                String previousStatus = extractPreviousStatus(metadataStr);

                em.createNativeQuery(
                    "UPDATE autofix_tasks SET status = 'FAILED', " +
                    "error_message = 'Worker crashed or stopped heartbeating', " +
                    "completed_at = NOW(3) " +
                    "WHERE task_id = :taskId")
                    .setParameter("taskId", taskId)
                    .executeUpdate();

                revertBugReportStatus(bugReportUuid, previousStatus);

                bugReportService.addComment(bugReportUuid, "system:autofix-reaper",
                    "Auto-fix task failed after all retries. Manual investigation needed. Task: " + taskId, true);

                log.infof("Reaped stale task %s -> FAILED (max retries exhausted)", taskId);
            }
        }
    }

    /**
     * Scenario 2: Tasks in COMPLETED/FAILED whose bug report is still in AUTO_FIX_REQUESTED.
     * Worker may have crashed between completing the task and transitioning the bug report.
     */
    private void reconcileOrphanedBugReports() {
        @SuppressWarnings("unchecked")
        List<Object[]> orphanedTasks = em.createNativeQuery(
            "SELECT t.task_id, t.bug_report_uuid, t.metadata " +
            "FROM autofix_tasks t " +
            "JOIN bug_reports b ON t.bug_report_uuid = b.uuid " +
            "WHERE t.status IN ('COMPLETED', 'FAILED') " +
            "AND b.status = 'AUTO_FIX_REQUESTED' " +
            "AND t.completed_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)")
            .getResultList();

        for (Object[] row : orphanedTasks) {
            String taskId = (String) row[0];
            String bugReportUuid = (String) row[1];
            String metadataStr = (String) row[2];
            String previousStatus = extractPreviousStatus(metadataStr);

            revertBugReportStatus(bugReportUuid, previousStatus);
            log.infof("Reconciled orphaned bug report %s -> %s (task %s)", bugReportUuid, previousStatus, taskId);
        }
    }

    /**
     * Scenario 3: Tasks in PROCESSING whose bug report has been CLOSED by admin.
     * Cancel the task so the worker (if still running) detects the cancellation.
     */
    private void cancelTasksForClosedReports() {
        @SuppressWarnings("unchecked")
        List<Object[]> closedReportTasks = em.createNativeQuery(
            "SELECT t.task_id, t.bug_report_uuid " +
            "FROM autofix_tasks t " +
            "JOIN bug_reports b ON t.bug_report_uuid = b.uuid " +
            "WHERE t.status = 'PROCESSING' " +
            "AND b.status = 'CLOSED'")
            .getResultList();

        for (Object[] row : closedReportTasks) {
            String taskId = (String) row[0];
            String bugReportUuid = (String) row[1];

            em.createNativeQuery(
                "UPDATE autofix_tasks SET status = 'CANCELLED', " +
                "error_message = 'Bug report was closed by admin', " +
                "completed_at = NOW(3) " +
                "WHERE task_id = :taskId AND status = 'PROCESSING'")
                .setParameter("taskId", taskId)
                .executeUpdate();

            bugReportService.addComment(bugReportUuid, "system:autofix-reaper",
                "Auto-fix task cancelled — bug report was closed. Task: " + taskId, true);

            log.infof("Cancelled task %s — bug report %s was closed", taskId, bugReportUuid);
        }
    }

    // --- Helpers ---

    private String extractPreviousStatus(String metadataStr) {
        try {
            if (metadataStr != null) {
                return MAPPER.readTree(metadataStr)
                    .path("previous_status")
                    .asText("SUBMITTED");
            }
        } catch (Exception ignored) {
            // Fall through to default
        }
        return "SUBMITTED";
    }

    private void revertBugReportStatus(String bugReportUuid, String previousStatus) {
        try {
            bugReportService.changeStatus(bugReportUuid, previousStatus,
                "system:autofix-reaper", null);
        } catch (Exception e) {
            log.warnf("Could not revert bug report %s to %s: %s",
                bugReportUuid, previousStatus, e.getMessage());
        }
    }
}
