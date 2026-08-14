package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The write and claim API of the Method B scheduling outbox (plan §8.3,
 * invoice-booking idiom). Two rules keep external writes exactly-once
 * enough:
 * <ol>
 *   <li><b>Enqueue is idempotent</b>: {@code INSERT IGNORE} on the
 *       unique idempotency key — a re-run sweep step re-<em>derives</em>
 *       the same intended actions and the duplicates vanish.</li>
 *   <li><b>Execution is claimed</b>: an atomic
 *       {@code PENDING → IN_PROGRESS} UPDATE decides which instance
 *       runs an action; the loser sees zero rows affected. A claim
 *       older than {@link #CLAIM_TIMEOUT_MINUTES} (crash mid-execution)
 *       becomes re-claimable — the executors' own idempotency (Graph
 *       {@code transactionId}, 404-tolerant deletes) covers the replay.</li>
 * </ol>
 * Callers enqueue INSIDE the transaction that makes the state change the
 * action belongs to — both commit or neither.
 */
@JBossLog
@ApplicationScoped
public class SchedulingOutboxService {

    /** Attempts before a row is dead-lettered (FAILED). */
    public static final int MAX_ATTEMPTS = 8;

    /** An IN_PROGRESS row older than this is presumed crashed. */
    public static final int CLAIM_TIMEOUT_MINUTES = 10;

    @Inject
    EntityManager em;

    /**
     * Enqueue one action, idempotently. Returns true when the row was
     * actually inserted (false = the same intended action already
     * exists, whatever its status).
     */
    public boolean enqueue(String requestUuid, String slotUuid,
                           SchedulingOutboxAction action, String qualifier,
                           String payloadJson) {
        String key = idempotencyKey(requestUuid, slotUuid, action, qualifier);
        int inserted = em.createNativeQuery(
                        "INSERT IGNORE INTO recruitment_scheduling_outbox "
                                + "(uuid, request_uuid, slot_uuid, action, idempotency_key, "
                                + " payload_json, status, attempt_count, next_attempt_at, "
                                + " created_at, updated_at) "
                                + "VALUES (UUID(), :request, :slot, :action, :key, "
                                + " :payload, 'PENDING', 0, NOW(3), NOW(3), NOW(3))")
                .setParameter("request", requestUuid)
                .setParameter("slot", slotUuid)
                .setParameter("action", action.name())
                .setParameter("key", key)
                .setParameter("payload", payloadJson)
                .executeUpdate();
        return inserted == 1;
    }

    /**
     * Rows due for execution: PENDING past their due time, plus
     * IN_PROGRESS rows whose claim went stale. Ordered oldest-first so
     * per-slot actions execute in creation order (sequential hold
     * creation keeps the compensation set explicit, plan §9.3).
     */
    public List<RecruitmentSchedulingOutbox> due(LocalDateTime now, int limit) {
        return RecruitmentSchedulingOutbox
                .<RecruitmentSchedulingOutbox>find(
                        "(status = ?1 and nextAttemptAt <= ?3) "
                                + "or (status = ?2 and claimedAt <= ?4)",
                        SchedulingOutboxStatus.PENDING,
                        SchedulingOutboxStatus.IN_PROGRESS,
                        now, now.minusMinutes(CLAIM_TIMEOUT_MINUTES))
                .page(0, limit)
                .list();
    }

    /**
     * Atomically claim one row. True = this instance owns the attempt.
     * The guard predicate mirrors {@link #due} so a stale IN_PROGRESS
     * claim is stealable but a fresh one is not.
     */
    public boolean claim(String uuid, LocalDateTime now) {
        int claimed = em.createNativeQuery(
                        "UPDATE recruitment_scheduling_outbox "
                                + "SET status = 'IN_PROGRESS', claimed_at = :now, "
                                + "    attempt_count = attempt_count + 1, updated_at = :now "
                                + "WHERE uuid = :uuid "
                                + "  AND ((status = 'PENDING' AND next_attempt_at <= :now) "
                                + "    OR (status = 'IN_PROGRESS' AND claimed_at <= :stale))")
                .setParameter("uuid", uuid)
                .setParameter("now", now)
                .setParameter("stale", now.minusMinutes(CLAIM_TIMEOUT_MINUTES))
                .executeUpdate();
        return claimed == 1;
    }

    /** The attempt succeeded. */
    public void complete(String uuid, LocalDateTime now) {
        RecruitmentSchedulingOutbox row = RecruitmentSchedulingOutbox.findById(uuid);
        if (row == null) {
            return;
        }
        row.setStatus(SchedulingOutboxStatus.COMPLETED);
        row.setCompletedAt(now);
        row.setClaimedAt(null);
    }

    /**
     * The attempt failed: back to PENDING with backoff, or FAILED
     * (dead-letter) once the attempt cap is reached. Returns the
     * resulting status for the dispatcher's logging.
     */
    public SchedulingOutboxStatus recordFailure(String uuid, String error, LocalDateTime now) {
        RecruitmentSchedulingOutbox row = RecruitmentSchedulingOutbox.findById(uuid);
        if (row == null) {
            return SchedulingOutboxStatus.FAILED;
        }
        row.setLastError(error);
        row.setClaimedAt(null);
        if (row.getAttemptCount() >= MAX_ATTEMPTS) {
            row.setStatus(SchedulingOutboxStatus.FAILED);
            return SchedulingOutboxStatus.FAILED;
        }
        row.setStatus(SchedulingOutboxStatus.PENDING);
        row.setNextAttemptAt(now.plusMinutes(backoffMinutes(row.getAttemptCount())));
        return SchedulingOutboxStatus.PENDING;
    }

    /** FAILED rows for a request — the cleanup-warning surface (§21.5). */
    public long failedCount(String requestUuid) {
        return RecruitmentSchedulingOutbox.count("requestUuid = ?1 and status = ?2",
                requestUuid, SchedulingOutboxStatus.FAILED);
    }

    // ---- Pure helpers (DB-free tested) ------------------------------------

    /**
     * {@code request+slot+action+qualifier} (plan §8.3). The qualifier
     * pins the action instance: an approval uuid for DMs, a hold uuid
     * for holds, a version/kind for request-level notices.
     */
    public static String idempotencyKey(String requestUuid, String slotUuid,
                                        SchedulingOutboxAction action, String qualifier) {
        return requestUuid + ":" + (slotUuid == null ? "-" : slotUuid)
                + ":" + action.name() + ":" + (qualifier == null ? "-" : qualifier);
    }

    /** Exponential backoff, capped at an hour: 2,4,8,…,60 minutes. */
    public static long backoffMinutes(int attemptCount) {
        long minutes = 1L << Math.min(attemptCount, 6);
        return Math.min(minutes, 60);
    }
}
