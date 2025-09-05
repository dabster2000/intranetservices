package dk.trustworks.intranet.batch.monitoring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Optional;

@ApplicationScoped
@JBossLog
public class BatchJobTrackingService {

    @Inject
    EntityManager em;

    /* =========================================================
       NEW: Utility to convert an exception's stack trace to String
       ========================================================= */
    public static String stackTraceOf(Throwable t) {
        if (t == null) return null;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /* =========================================================
       NEW: Persist failure + trace in its own transaction
       (so logs survive even if the job/step tx rolls back)
       ========================================================= */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void onJobFailure(long executionId, Throwable error, String exitStatus) {
        log.infof("[BATCH-MONITOR] onJobFailure called for execution %d with exitStatus: %s", executionId, exitStatus);
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) {
            log.warnf("[BATCH-MONITOR] Cannot update job failure - no tracking record found for execution %d", executionId);
            return;
        }
        e.setStatus("FAILED");
        e.setResult("FAILED");
        if (exitStatus != null && !exitStatus.isBlank()) {
            e.setExitStatus(exitStatus);
        }
        e.setEndTime(LocalDateTime.now());
        e.setTraceLog(stackTraceOf(error));
        em.merge(e);
        log.infof("[BATCH-MONITOR] Job failure recorded for execution %d", executionId);
    }

    /* =========================================================
       NEW: Only set/append the trace (useful from any listener)
       ========================================================= */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void setTrace(long executionId, Throwable error) {
        log.debugf("[BATCH-MONITOR] Setting trace for execution %d", executionId);
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) {
            log.warnf("[BATCH-MONITOR] Cannot set trace - no tracking record found for execution %d", executionId);
            return;
        }
        String newTrace = Optional.ofNullable(stackTraceOf(error)).orElse("");
        String existing = Optional.ofNullable(e.getTraceLog()).orElse("");
        e.setTraceLog(existing.isBlank() ? newTrace : existing + "\n\n" + newTrace);
        em.merge(e);
        log.debugf("[BATCH-MONITOR] Trace set for execution %d", executionId);
    }

    /* =========================================================
       CHANGED: Overload onJobEnd to optionally capture Throwable
       ========================================================= */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void onJobEnd(long executionId, String batchStatus, String exitStatus, Throwable error) {
        // Keep your existing behavior
        onJobEnd(executionId, batchStatus, exitStatus);
        // If a caller has the Throwable, persist it safely in a new tx
        if (error != null) {
            setTrace(executionId, error);
        }
    }

    // ----------------- YOUR EXISTING METHODS (unchanged) -----------------

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void onJobStart(long executionId, String jobName) {
        log.infof("[BATCH-MONITOR] onJobStart called for job '%s' execution %d", jobName, executionId);
        try {
            // ALWAYS create a new record - NEVER update existing ones
            // This prevents data corruption when JBatch reuses execution IDs after server restart
            log.infof("[BATCH-MONITOR] Creating new tracking record for job '%s' execution %d", jobName, executionId);
            
            BatchJobExecutionTracking e = new BatchJobExecutionTracking();
            e.setExecutionId(executionId);
            e.setJobName(jobName);
            e.setStatus("STARTED");
            e.setStartTime(LocalDateTime.now());
            e.setProgressPercent(0);
            e.setCompletedSubtasks(0);
            
            em.persist(e);
            em.flush(); // Force immediate persistence
            
            log.infof("[BATCH-MONITOR] Successfully created tracking record (id=%d) for job '%s' execution %d", 
                     e.getId(), jobName, executionId);
        } catch (Exception ex) {
            log.errorf(ex, "[BATCH-MONITOR] Failed to create tracking record for job '%s' execution %d", jobName, executionId);
            throw ex;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void onJobEnd(long executionId, String batchStatus, String exitStatus) {
        log.infof("[BATCH-MONITOR] onJobEnd called for execution %d - batchStatus: %s, exitStatus: %s", 
                 executionId, batchStatus, exitStatus);
        try {
            // Small delay to allow partition progress updates to commit
            // This helps avoid race condition where job completion is checked before partition updates are persisted
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            
            BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
            if (e == null) {
                log.errorf("[BATCH-MONITOR] CRITICAL: Cannot update job end - no tracking record found for execution %d. " +
                          "This means onJobStart likely failed or the record was deleted.", executionId);
                return;
            }
            
            log.debugf("[BATCH-MONITOR] Found tracking record for execution %d, current status: %s, completed: %d, total: %d", 
                      executionId, e.getStatus(), e.getCompletedSubtasks(), e.getTotalSubtasks());
            
            e.setStatus(batchStatus);
            e.setExitStatus(exitStatus);
            e.setEndTime(LocalDateTime.now());
            String result;
            if ("COMPLETED".equalsIgnoreCase(batchStatus)) {
                // For jobs with subtasks, only mark as PARTIAL if we have a significant discrepancy
                // Allow for minor race conditions (e.g., off by 1 or 2)
                if (e.getTotalSubtasks() != null && e.getCompletedSubtasks() != null
                        && e.getTotalSubtasks() > 0) {
                    int difference = e.getTotalSubtasks() - e.getCompletedSubtasks();
                    if (difference > 2) {
                        result = "PARTIAL";
                        log.infof("[BATCH-MONITOR] Job marked as PARTIAL - completed %d of %d subtasks (difference: %d)", 
                                 e.getCompletedSubtasks(), e.getTotalSubtasks(), difference);
                    } else {
                        result = "COMPLETED";
                        log.infof("[BATCH-MONITOR] Job marked as COMPLETED - completed %d of %d subtasks", 
                                 e.getCompletedSubtasks(), e.getTotalSubtasks());
                    }
                } else {
                    result = "COMPLETED";
                    log.infof("[BATCH-MONITOR] Job marked as COMPLETED");
                }
                e.setProgressPercent(100);
            } else if ("FAILED".equalsIgnoreCase(batchStatus) || "STOPPED".equalsIgnoreCase(batchStatus)) {
                result = "FAILED";
                log.infof("[BATCH-MONITOR] Job marked as FAILED (status: %s)", batchStatus);
            } else {
                result = batchStatus; // fallback
                log.warnf("[BATCH-MONITOR] Unknown batch status '%s', using as result", batchStatus);
            }
            e.setResult(result);
            em.merge(e);
            em.flush(); // Force immediate persistence
            log.infof("[BATCH-MONITOR] Successfully updated job end for execution %d with result: %s", 
                     executionId, result);
        } catch (Exception ex) {
            log.errorf(ex, "[BATCH-MONITOR] Failed to update job end for execution %d", executionId);
            throw ex;
        }
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void incrementTotalSubtasks(long executionId) {
        log.debugf("[BATCH-MONITOR] Incrementing total subtasks for execution %d", executionId);
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) {
            log.warnf("[BATCH-MONITOR] Cannot increment total subtasks - no tracking record found for execution %d", executionId);
            return;
        }
        Integer total = Optional.ofNullable(e.getTotalSubtasks()).orElse(0);
        e.setTotalSubtasks(total + 1);
        if (e.getCompletedSubtasks() != null && e.getTotalSubtasks() != null && e.getTotalSubtasks() > 0) {
            int completed = Optional.ofNullable(e.getCompletedSubtasks()).orElse(0);
            int percent = Math.min(100, (int) Math.floor((completed * 100.0) / e.getTotalSubtasks()));
            e.setProgressPercent(percent);
        }
        em.merge(e);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void incrementCompletedSubtasks(long executionId) {
        log.debugf("[BATCH-MONITOR] Incrementing completed subtasks for execution %d", executionId);
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) {
            log.warnf("[BATCH-MONITOR] Cannot increment completed subtasks - no tracking record found for execution %d", executionId);
            return;
        }
        Integer done = Optional.ofNullable(e.getCompletedSubtasks()).orElse(0);
        e.setCompletedSubtasks(done + 1);
        if (e.getTotalSubtasks() != null && e.getTotalSubtasks() > 0) {
            int percent = Math.min(100, (int) Math.floor((e.getCompletedSubtasks() * 100.0) / e.getTotalSubtasks()));
            e.setProgressPercent(percent);
        }
        em.merge(e);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void appendDetails(long executionId, String message) {
        log.debugf("[BATCH-MONITOR] Appending details for execution %d: %s", executionId, message);
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) {
            log.warnf("[BATCH-MONITOR] Cannot append details - no tracking record found for execution %d", executionId);
            return;
        }
        String existing = Optional.ofNullable(e.getDetails()).orElse("");
        String newDetails = existing.isEmpty() ? message : existing + "\n" + message;
        e.setDetails(newDetails);
        em.merge(e);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void setTotalSubtasks(long executionId, int total) {
        log.infof("[BATCH-MONITOR] Setting total subtasks to %d for execution %d", total, executionId);
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) {
            log.warnf("[BATCH-MONITOR] Cannot set total subtasks - no tracking record found for execution %d", executionId);
            return;
        }
        e.setTotalSubtasks(total);
        if (e.getCompletedSubtasks() != null && total > 0) {
            int percent = Math.min(100, (int) Math.floor((e.getCompletedSubtasks() * 100.0) / total));
            e.setProgressPercent(percent);
        }
        em.merge(e);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void setCompletedSubtasksSynchronous(long executionId, int completed) {
        log.debugf("[BATCH-MONITOR] Setting completed subtasks to %d for execution %d (synchronous)", completed, executionId);
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) {
            log.warnf("[BATCH-MONITOR] Cannot set completed subtasks - no tracking record found for execution %d", executionId);
            return;
        }
        e.setCompletedSubtasks(completed);
        if (e.getTotalSubtasks() != null && e.getTotalSubtasks() > 0) {
            int percent = Math.min(100, (int) Math.floor((completed * 100.0) / e.getTotalSubtasks()));
            e.setProgressPercent(percent);
        }
        em.merge(e);
        em.flush(); // Force immediate persistence to avoid race condition
    }

    private BatchJobExecutionTracking findByExecutionIdForUpdate(long executionId) {
        try {
            // Find the most recent record with this execution_id that hasn't ended yet
            // This handles the case where execution IDs are reused after server restart
            BatchJobExecutionTracking e = em.createQuery(
                    "SELECT e FROM BatchJobExecutionTracking e " +
                    "WHERE e.executionId = :id " +
                    "AND e.endTime IS NULL " +
                    "ORDER BY e.startTime DESC", 
                    BatchJobExecutionTracking.class)
                .setParameter("id", executionId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(1)
                .getSingleResult();

            return e;
        } catch (Exception ex) {
            log.warnf("[BATCH-MONITOR] Failed to find/lock active tracking record for execution %d: %s", 
                     executionId, ex.getMessage());
            return null;
        }
    }

    // Method removed - no longer needed since we always INSERT in onJobStart
    // Previously used to check for existing records, but that caused data corruption
    // when JBatch reused execution IDs after server restart
}
