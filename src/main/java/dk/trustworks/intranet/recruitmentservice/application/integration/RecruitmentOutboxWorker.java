package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.HandlerOutcome;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.HandlerResult;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Periodic drainer for the recruitment outbox table.
 *
 * <p>Every 30s it polls a small batch of due PENDING rows
 * ({@code next_retry_at <= now}) using {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * (via JPA pessimistic write + {@code jakarta.persistence.lock.timeout = -2}).
 * Each row is dispatched in its own transaction.
 *
 * <h3>Retry policy (canonical)</h3>
 * Backoff after attempt N is: 1 → 1 minute, 2 → 5 minutes, 3 → 30 minutes,
 * 4+ → 2 hours. After 5 retryable failures the row is moved to {@link OutboxStatus#FAILED}
 * (terminal) — see {@link #isTerminalAt(int)}.
 *
 * <h3>Outcome mapping</h3>
 * <ul>
 *     <li>{@code OK} → status DONE, {@code lastError = null}</li>
 *     <li>{@code RETRYABLE} → status PENDING, attempt++ , next_retry_at = now + backoff</li>
 *     <li>{@code TERMINAL} → status FAILED, {@code lastError = handler.error}</li>
 * </ul>
 */
@ApplicationScoped
public class RecruitmentOutboxWorker {

    private static final Logger LOG = Logger.getLogger(RecruitmentOutboxWorker.class);

    /** Maximum retryable attempts before a row is parked as FAILED. */
    static final int MAX_RETRYABLE_ATTEMPTS = 5;

    /** Batch size — small to keep transactions short. */
    static final int BATCH_SIZE = 25;

    @Inject EntityManager em;
    @Inject OutboxDispatcher dispatcher;

    @Scheduled(every = "30s", concurrentExecution = ConcurrentExecution.SKIP, identity = "recruitment-outbox-drain")
    void drain() {
        try {
            int processed = drainBatch();
            if (processed > 0) {
                LOG.debugf("RecruitmentOutboxWorker: drained %d rows", processed);
            }
        } catch (Exception ex) {
            LOG.warnf(ex, "RecruitmentOutboxWorker: drain tick failed");
        }
    }

    @Transactional
    int drainBatch() {
        List<String> dueUuids = findDueUuids(BATCH_SIZE);
        int processed = 0;
        for (String uuid : dueUuids) {
            if (dispatchOne(uuid)) {
                processed++;
            }
        }
        return processed;
    }

    @SuppressWarnings("unchecked")
    private List<String> findDueUuids(int batchSize) {
        Query q = em.createQuery(
                "SELECT r.uuid FROM RecruitmentOutboxRow r " +
                "WHERE r.status = :pending AND r.nextRetryAt <= :now " +
                "ORDER BY r.nextRetryAt ASC");
        q.setParameter("pending", OutboxStatus.PENDING);
        q.setParameter("now", LocalDateTime.now());
        q.setMaxResults(batchSize);
        return (List<String>) q.getResultList();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean dispatchOne(String uuid) {
        RecruitmentOutboxRow row = lockRow(uuid);
        if (row == null || row.status != OutboxStatus.PENDING) {
            return false;
        }
        row.status = OutboxStatus.IN_FLIGHT;
        row.attemptCount++;
        row.lastAttemptAt = LocalDateTime.now();
        row.updatedAt = LocalDateTime.now();
        em.merge(row);

        HandlerResult result;
        try {
            result = dispatcher.dispatch(row);
        } catch (Exception ex) {
            LOG.warnf(ex, "RecruitmentOutboxWorker: handler threw row=%s", uuid);
            result = HandlerResult.retryable("dispatcher_threw: " + ex.getMessage());
        }

        applyOutcome(row, result);
        return true;
    }

    private RecruitmentOutboxRow lockRow(String uuid) {
        try {
            Query q = em.createQuery(
                    "SELECT r FROM RecruitmentOutboxRow r WHERE r.uuid = :uuid");
            q.setParameter("uuid", uuid);
            q.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            // SKIP LOCKED hint — silently ignored if the dialect doesn't support it.
            q.setHint("jakarta.persistence.lock.timeout", -2);
            List<?> rows = q.getResultList();
            return rows.isEmpty() ? null : (RecruitmentOutboxRow) rows.get(0);
        } catch (Exception ex) {
            LOG.debugf("RecruitmentOutboxWorker: row %s already locked or missing", uuid);
            return null;
        }
    }

    private void applyOutcome(RecruitmentOutboxRow row, HandlerResult result) {
        LocalDateTime now = LocalDateTime.now();
        row.updatedAt = now;
        if (result.outcome() == HandlerOutcome.OK) {
            row.status = OutboxStatus.DONE;
            row.lastError = null;
        } else if (result.outcome() == HandlerOutcome.TERMINAL) {
            row.status = OutboxStatus.FAILED;
            row.lastError = result.error();
        } else if (isTerminalAt(row.attemptCount)) {
            row.status = OutboxStatus.FAILED;
            row.lastError = "max_retries_exceeded: " + result.error();
        } else {
            row.status = OutboxStatus.PENDING;
            row.lastError = result.error();
            row.nextRetryAt = now.plusSeconds(backoffFor(row.attemptCount).getSeconds());
        }
        em.merge(row);
    }

    /** Returns the canonical backoff for the Nth retryable attempt. */
    static java.time.Duration backoffFor(int attempt) {
        return switch (attempt) {
            case 1 -> java.time.Duration.ofMinutes(1);
            case 2 -> java.time.Duration.ofMinutes(5);
            case 3 -> java.time.Duration.ofMinutes(30);
            default -> java.time.Duration.ofHours(2);
        };
    }

    /** {@code true} once the row's retryable budget is exhausted. */
    static boolean isTerminalAt(int attempt) {
        return attempt >= MAX_RETRYABLE_ATTEMPTS;
    }
}
