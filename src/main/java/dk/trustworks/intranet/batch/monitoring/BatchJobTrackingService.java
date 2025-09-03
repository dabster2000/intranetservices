package dk.trustworks.intranet.batch.monitoring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Optional;

@ApplicationScoped
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
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) return;
        e.setStatus("FAILED");
        e.setResult("FAILED");
        if (exitStatus != null && !exitStatus.isBlank()) {
            e.setExitStatus(exitStatus);
        }
        e.setEndTime(LocalDateTime.now());
        e.setTraceLog(stackTraceOf(error));
        em.merge(e);
    }

    /* =========================================================
       NEW: Only set/append the trace (useful from any listener)
       ========================================================= */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void setTrace(long executionId, Throwable error) {
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) return;
        String newTrace = Optional.ofNullable(stackTraceOf(error)).orElse("");
        String existing = Optional.ofNullable(e.getTraceLog()).orElse("");
        e.setTraceLog(existing.isBlank() ? newTrace : existing + "\n\n" + newTrace);
        em.merge(e);
    }

    /* =========================================================
       CHANGED: Overload onJobEnd to optionally capture Throwable
       ========================================================= */
    @Transactional
    public void onJobEnd(long executionId, String batchStatus, String exitStatus, Throwable error) {
        // Keep your existing behavior
        onJobEnd(executionId, batchStatus, exitStatus);
        // If a caller has the Throwable, persist it safely in a new tx
        if (error != null) {
            setTrace(executionId, error);
        }
    }

    // ----------------- YOUR EXISTING METHODS (unchanged) -----------------

    @Transactional
    public void onJobStart(long executionId, String jobName) {
        BatchJobExecutionTracking existing = findByExecutionId(executionId).orElse(null);
        if (existing == null) {
            BatchJobExecutionTracking e = new BatchJobExecutionTracking();
            e.setExecutionId(executionId);
            e.setJobName(jobName);
            e.setStatus("STARTED");
            e.setStartTime(LocalDateTime.now());
            e.setProgressPercent(0);
            e.setCompletedSubtasks(0);
            em.persist(e);
        } else {
            existing.setStatus("STARTED");
            if (existing.getStartTime() == null) {
                existing.setStartTime(LocalDateTime.now());
            }
            em.merge(existing);
        }
    }

    @Transactional
    public void onJobEnd(long executionId, String batchStatus, String exitStatus) {
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) return;
        e.setStatus(batchStatus);
        e.setExitStatus(exitStatus);
        e.setEndTime(LocalDateTime.now());
        String result;
        if ("COMPLETED".equalsIgnoreCase(batchStatus)) {
            if (e.getTotalSubtasks() != null && e.getCompletedSubtasks() != null
                    && e.getTotalSubtasks() > 0 && e.getCompletedSubtasks() < e.getTotalSubtasks()) {
                result = "PARTIAL";
            } else {
                result = "COMPLETED";
            }
            e.setProgressPercent(100);
        } else if ("FAILED".equalsIgnoreCase(batchStatus) || "STOPPED".equalsIgnoreCase(batchStatus)) {
            result = "FAILED";
        } else {
            result = batchStatus; // fallback
        }
        e.setResult(result);
        em.merge(e);
    }

    @Transactional
    public void incrementTotalSubtasks(long executionId) {
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) return;
        Integer total = Optional.ofNullable(e.getTotalSubtasks()).orElse(0);
        e.setTotalSubtasks(total + 1);
        if (e.getCompletedSubtasks() != null && e.getTotalSubtasks() != null && e.getTotalSubtasks() > 0) {
            int completed = Optional.ofNullable(e.getCompletedSubtasks()).orElse(0);
            int percent = Math.min(100, (int) Math.floor((completed * 100.0) / e.getTotalSubtasks()));
            e.setProgressPercent(percent);
        }
        em.merge(e);
    }

    @Transactional
    public void incrementCompletedSubtasks(long executionId) {
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) return;
        Integer done = Optional.ofNullable(e.getCompletedSubtasks()).orElse(0);
        e.setCompletedSubtasks(done + 1);
        if (e.getTotalSubtasks() != null && e.getTotalSubtasks() > 0) {
            int percent = Math.min(100, (int) Math.floor((e.getCompletedSubtasks() * 100.0) / e.getTotalSubtasks()));
            e.setProgressPercent(percent);
        }
        em.merge(e);
    }

    @Transactional
    public void appendDetails(long executionId, String message) {
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) return;
        String existing = Optional.ofNullable(e.getDetails()).orElse("");
        String newDetails = existing.isEmpty() ? message : existing + "\n" + message;
        e.setDetails(newDetails);
        em.merge(e);
    }

    @Transactional
    public void setTotalSubtasks(long executionId, int total) {
        BatchJobExecutionTracking e = findByExecutionIdForUpdate(executionId);
        if (e == null) return;
        e.setTotalSubtasks(total);
        if (e.getCompletedSubtasks() != null && total > 0) {
            int percent = Math.min(100, (int) Math.floor((e.getCompletedSubtasks() * 100.0) / total));
            e.setProgressPercent(percent);
        }
        em.merge(e);
    }

    private BatchJobExecutionTracking findByExecutionIdForUpdate(long executionId) {
        try {
            BatchJobExecutionTracking e = em.createQuery(
                            "select e from BatchJobExecutionTracking e where e.executionId = :id", BatchJobExecutionTracking.class)
                    .setParameter("id", executionId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getSingleResult();
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    private Optional<BatchJobExecutionTracking> findByExecutionId(long executionId) {
        try {
            BatchJobExecutionTracking e = em.createQuery(
                            "select e from BatchJobExecutionTracking e where e.executionId = :id", BatchJobExecutionTracking.class)
                    .setParameter("id", executionId)
                    .getSingleResult();
            return Optional.ofNullable(e);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
