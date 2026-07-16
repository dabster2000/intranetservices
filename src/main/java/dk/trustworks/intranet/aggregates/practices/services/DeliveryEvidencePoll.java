package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker.DeliveryPollResult;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker.WatermarkConflictException;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

/**
 * Commit-order-safe, low-contention cursor protocol for the once-per-minute delivery-evidence poll.
 *
 * <h2>The two problems this seam solves</h2>
 * <ol>
 *   <li><b>Commit order is not allocation order.</b> A non-locking {@code SELECT COALESCE(MAX(id),0)}
 *       reads the highest <em>committed</em> id, but InnoDB allocates AUTO_INCREMENT out of commit
 *       order: writer&nbsp;A can hold id&nbsp;11 uncommitted while writer&nbsp;B commits&nbsp;12, so a MAX
 *       read that advanced the cursor to&nbsp;12 would skip row&nbsp;11 forever once A commits.</li>
 *   <li><b>An open-ended {@code WHERE id>:cursor FOR UPDATE} held across the heavy bounds join</b>
 *       takes the supremum next-key lock and blocks every {@code fact_change_log} INSERT (i.e. every
 *       work registration) for the whole 2&ndash;2.5&nbsp;s join. That violates the p95 +5&nbsp;ms budget.</li>
 * </ol>
 *
 * <h2>Two-transaction protocol</h2>
 * The poll is split into two short {@code REQUIRES_NEW} transactions so no lock is ever held across
 * the heavy relevance join:
 * <ul>
 *   <li><b>TX1 &mdash; settle (short, {@code publication → fact_change_log}).</b> Publication status is
 *       X-locked ({@link #PUBLICATION_LOCK_SQL}); a {@code RUNNING} attempt defers. Otherwise a
 *       non-locking {@code T = }{@link #LOG_MAX_SQL} is read, then a <em>bounded</em> settle scan
 *       {@link #LOG_SETTLE_LOCK_SQL} ({@code WHERE id>:cursor AND id<=:T ORDER BY id FOR UPDATE}) waits
 *       only for in-flight allocations {@code ≤ T} and never supremum-locks while rows {@code > T}
 *       exist. {@code settledTarget} is the highest actually-locked id (or the cursor if none). TX1
 *       commits immediately, releasing all log locks &mdash; it holds no lock across any join.</li>
 *   <li><b>TX2 &mdash; advance (short, watermark-only).</b> A NON-locking snapshot bounds query
 *       ({@link #POLL_BOUNDS_SQL}) runs over exactly {@code (cursor, settledTarget]} &mdash; every id
 *       there is already committed and immutable (its snapshot begins after TX1 commit). Only then is
 *       the watermark X-locked, retention-gap handled, and the cursor advanced with a CAS
 *       ({@link #ADVANCE_SQL}) guarded by {@code last_fact_change_log_id=:cursor}. TX2 takes no log
 *       lock, so it can never invert against the trigger writers.</li>
 * </ul>
 *
 * <h2>Why this is still correct</h2>
 * The settle scan proves every id {@code ≤ settledTarget} is committed or a permanent hole before the
 * cursor may advance past it (it blocks on any lower in-flight allocation), so no lower id is ever
 * skipped. Relevance is computed over exactly the settled range. The CAS keeps single-advance: a
 * concurrent poller that advanced the cursor makes {@code last_fact_change_log_id=:cursor} match zero
 * rows, so this poll returns already-consumed without a second version bump. Permanent numeric holes
 * are still ignored.
 *
 * <h2>Deadlock-freedom</h2>
 * TX1 acquires {@code publication → fact_change_log} (matching the attempt CAS's publication-first
 * order and never taking the watermark). TX2 takes the watermark only (no log lock). The V412
 * {@code trg_contract_consultants_*} triggers insert {@code fact_change_log} then update the
 * watermark; since neither poll transaction ever holds the log while waiting for the watermark, no
 * lock-order inversion is possible.
 *
 * <p>The order logic lives here once and is exercised by production (an EntityManager-backed gateway
 * wrapped in two {@code REQUIRES_NEW} methods), by unit tests (mock gateway/transactions), and by the
 * real-MariaDB integration test (a raw-JDBC gateway committing between the phases). The final-attempt
 * union scan keeps its own log-before-watermark ordering (see {@code PracticeRevenueDirtyMarker}).
 */
final class DeliveryEvidencePoll {

    private DeliveryEvidencePoll() {
    }

    /** Non-locking routing snapshot; the advance decision is always re-taken under the watermark lock. */
    static final String POLL_WATERMARK_SNAPSHOT_SQL = """
            SELECT last_fact_change_log_id,last_pruned_fact_change_log_id,source_state,
                   recovery_token,retention_gap_reason
            FROM practice_revenue_source_watermark
            WHERE source_name='DELIVERY_EVIDENCE'
            """;

    /** Serializes the settle phase against the attempt RUNNING/publish transition (publication-first). */
    static final String PUBLICATION_LOCK_SQL = """
            SELECT status,published_generation_id,attempt_generation_id
            FROM practice_revenue_publication
            WHERE publication_key='PRACTICE_CONTRIBUTION' FOR UPDATE
            """;

    /** Non-locking upper bound for the settle scan; observing a committed high-water is cheap and safe. */
    static final String LOG_MAX_SQL = "SELECT COALESCE(MAX(id),0) FROM fact_change_log";

    /**
     * Bounded, commit-order-safe settle scan. Locking exactly {@code (cursor, T]} waits for in-flight
     * allocations {@code ≤ T} but never supremum-locks while rows {@code > T} exist, and it is held only
     * for the microseconds of TX1 (no heavy join runs under it). A lower id that commits after a higher
     * one can never be skipped: the scan blocks on it, or the slot is a permanent hole.
     */
    static final String LOG_SETTLE_LOCK_SQL = """
            SELECT id FROM fact_change_log
            WHERE id>:cursor AND id<=:target ORDER BY id FOR UPDATE
            """;

    /**
     * Open-ended locking horizon used only by the attempt-owned final union scan (which legitimately
     * consumes the whole current tail under the revenue lock). The ordinary poll never uses it.
     */
    static final String LOG_HORIZON_LOCK_SQL = """
            SELECT id FROM fact_change_log
            WHERE id>:cursor ORDER BY id FOR UPDATE
            """;

    /** Authoritative watermark row, X-locked in TX2 (and, for the final scan, after the log range). */
    static final String WATERMARK_LOCK_SQL = """
            SELECT last_fact_change_log_id,last_pruned_fact_change_log_id,source_state,
                   recovery_token,retention_gap_reason
            FROM practice_revenue_source_watermark
            WHERE source_name='DELIVERY_EVIDENCE' FOR UPDATE
            """;

    /**
     * Published-generation relevance bounds over the settled {@code (cursor, target]} window. Runs
     * NON-locking in TX2: every id in that range was proven committed by TX1's settle scan, so a
     * consistent snapshot begun after TX1 commit sees them all.
     */
    static final String POLL_BOUNDS_SQL = """
            SELECT MIN(d.dependent_recognized_month),MAX(d.dependent_recognized_month)
            FROM fact_change_log f
            JOIN practice_revenue_publication p
              ON p.publication_key='PRACTICE_CONTRIBUTION'
            JOIN fact_practice_revenue_dependency_mat d
              ON d.generation_id=p.published_generation_id
            LEFT JOIN work live_work
              ON f.change_type='WORK' AND live_work.uuid=f.source_id
            LEFT JOIN task live_task ON live_task.uuid=live_work.taskuuid
            LEFT JOIN project live_project ON live_project.uuid=live_task.projectuuid
            LEFT JOIN contract_project live_contract_project
              ON live_contract_project.projectuuid=live_project.uuid
            LEFT JOIN contract_consultants live_contract_consultant
              ON f.change_type='CONTRACT'
             AND live_contract_consultant.uuid=f.source_id
            WHERE f.id>:cursor AND f.id<=:target
              AND f.change_type IN ('WORK','CONTRACT')
              AND d.dependency_source_category='DELIVERY_EVIDENCE'
              AND (
                (f.change_type='WORK' AND (
                   d.source_work_uuid=f.source_id
                   OR (d.source_user_uuid=f.useruuid
                       AND f.affected_date>=d.delivery_start_date
                       AND f.affected_date<d.delivery_end_date)
                   OR (f.affected_date>=d.delivery_start_date
                       AND f.affected_date<d.delivery_end_date
                       AND (d.source_task_uuid=live_task.uuid
                            OR d.source_project_uuid=live_project.uuid
                            OR d.source_contract_project_uuid=live_contract_project.uuid
                            OR d.source_contract_uuid=live_contract_project.contractuuid))
                ))
                OR
                (f.change_type='CONTRACT' AND (
                   d.source_contract_consultant_uuid=f.source_id
                   OR (d.source_user_uuid=f.useruuid
                       AND f.affected_date>=d.delivery_start_date
                       AND f.affected_date<d.delivery_end_date)
                   OR (d.source_contract_uuid=live_contract_consultant.contractuuid
                       AND d.source_capacity_user_uuid=live_contract_consultant.useruuid
                       AND f.affected_date>=d.delivery_start_date
                       AND f.affected_date<d.delivery_end_date)
                ))
              )
            """;

    /** Single guarded advance. The {@code last_fact_change_log_id=:cursor} predicate keeps it to one row. */
    static final String ADVANCE_SQL = """
            UPDATE practice_revenue_source_watermark
            SET last_fact_change_log_id=:target,
                last_observed_at=UTC_TIMESTAMP(6),
                source_version=source_version+IF(:relevant=1,1,0),
                changed_at=IF(:relevant=1,UTC_TIMESTAMP(6),changed_at),
                affected_start_month=CASE
                  WHEN :relevant=0 THEN affected_start_month
                  WHEN affected_start_month IS NULL THEN :affectedStart
                  ELSE LEAST(affected_start_month,:affectedStart) END,
                affected_end_month=CASE
                  WHEN :relevant=0 THEN affected_end_month
                  WHEN affected_end_month IS NULL THEN :affectedEnd
                  ELSE GREATEST(affected_end_month,:affectedEnd) END,
                safe_reason=IF(:relevant=1,'DELIVERY_EVIDENCE_CHANGED',safe_reason),
                optimistic_version=optimistic_version+1
            WHERE source_name='DELIVERY_EVIDENCE' AND source_state='READY'
              AND recovery_token IS NULL AND retention_gap_reason IS NULL
              AND last_fact_change_log_id=:cursor
            """;

    /** Fail-closed retention-gap transition; guarded so a concurrent mover cannot repeat it. */
    static final String RETENTION_FAIL_SQL = """
            UPDATE practice_revenue_source_watermark
            SET source_state='FAILED',attempt_token=NULL,started_at=NULL,
                completed_at=UTC_TIMESTAMP(6),last_observed_at=UTC_TIMESTAMP(6),
                safe_reason='FACT_CHANGE_LOG_RETENTION_GAP',
                retention_gap_reason='FACT_CHANGE_LOG_RETENTION_GAP',
                optimistic_version=optimistic_version+1
            WHERE source_name='DELIVERY_EVIDENCE'
              AND last_fact_change_log_id=:cursor
              AND last_pruned_fact_change_log_id=:pruned
              AND recovery_token IS NULL
            """;

    /**
     * Orchestrates the two transactions. Each {@link DeliveryPollTransactions} method runs in its own
     * {@code REQUIRES_NEW} transaction; this method holds none, so the settle and advance phases never
     * share a transaction and no lock spans them.
     */
    static DeliveryPollResult poll(DeliveryPollTransactions transactions) {
        SettleOutcome outcome = transactions.settle();
        return switch (outcome.kind()) {
            case DEFERRED -> DeliveryPollResult.deferred(outcome.cursor());
            case WATERMARK_ONLY -> transactions.resolveWatermarkOnly();
            case SETTLED -> outcome.settledTarget().compareTo(outcome.cursor()) <= 0
                    ? new DeliveryPollResult(false, false, outcome.cursor(), outcome.cursor(), null, null)
                    : transactions.advance(outcome.cursor(), outcome.settledTarget());
        };
    }

    /** TX1 body: publication guard + bounded settle scan. Commits in the caller's REQUIRES_NEW method. */
    static SettleOutcome settle(DeliveryPollGateway gateway) {
        DeliveryWatermark snapshot = gateway.snapshot();
        if (!snapshot.advanceable()) {
            // Retention gap or not-READY: no log lock here; TX2 resolves it under the watermark lock.
            return SettleOutcome.watermarkOnly(snapshot.cursor());
        }
        if ("RUNNING".equals(gateway.lockPublicationStatus())) {
            // The running attempt owns the final union scan; leave the tail for it.
            return SettleOutcome.deferred(snapshot.cursor());
        }
        BigInteger max = gateway.maxLogId();
        BigInteger settledTarget = gateway.settleHorizon(snapshot.cursor(), max);
        return SettleOutcome.settled(snapshot.cursor(), settledTarget);
    }

    /** TX2 body for a settled range: non-locking bounds, then a watermark-only guarded advance. */
    static DeliveryPollResult advance(DeliveryPollGateway gateway, BigInteger cursor,
                                      BigInteger settledTarget) {
        DeliveryBounds bounds = gateway.pollBounds(cursor, settledTarget);
        DeliveryWatermark watermark = gateway.lockWatermark();
        DeliveryPollResult gap = handleRetentionGap(gateway, watermark);
        if (gap != null) {
            return gap;
        }
        if (!watermark.ready()) {
            return DeliveryPollResult.deferred(watermark.cursor());
        }
        if (settledTarget.compareTo(watermark.cursor()) <= 0) {
            // A concurrent poller already advanced through the settled range.
            return new DeliveryPollResult(false, false, watermark.cursor(), watermark.cursor(), null, null);
        }
        boolean relevant = bounds.affectedStart() != null;
        if (!gateway.advance(cursor, settledTarget, bounds.affectedStart(), bounds.affectedEnd(), relevant)) {
            // The CAS lost to a concurrent advance: the settled range is already consumed, never twice.
            return new DeliveryPollResult(false, false, watermark.cursor(), watermark.cursor(), null, null);
        }
        return new DeliveryPollResult(false, relevant, cursor, settledTarget,
                bounds.affectedStart(), bounds.affectedEnd());
    }

    /** TX2 body for the not-advanceable case: watermark-only retention-gap handling or defer. */
    static DeliveryPollResult resolveWatermarkOnly(DeliveryPollGateway gateway) {
        DeliveryWatermark watermark = gateway.lockWatermark();
        DeliveryPollResult gap = handleRetentionGap(gateway, watermark);
        if (gap != null) {
            return gap;
        }
        // The routing snapshot was stale (or the source is mid-attempt/recovery); self-heals next poll.
        return DeliveryPollResult.deferred(watermark.cursor());
    }

    private static DeliveryPollResult handleRetentionGap(DeliveryPollGateway gateway,
                                                         DeliveryWatermark watermark) {
        if (watermark.cursor().compareTo(watermark.pruned()) >= 0) {
            return null;
        }
        if (watermark.failedWithRetentionGap()) {
            return DeliveryPollResult.deferred(watermark.cursor());
        }
        if (!gateway.failRetentionGap(watermark.cursor(), watermark.pruned())) {
            throw new WatermarkConflictException("DELIVERY_EVIDENCE_RETENTION_GAP_CONFLICT");
        }
        return new DeliveryPollResult(false, true, watermark.cursor(), watermark.cursor(), null, null);
    }

    /** Immutable view of the delivery watermark row read by the poll. */
    record DeliveryWatermark(BigInteger cursor, BigInteger pruned, String state,
                             boolean hasRecoveryToken, String retentionGapReason) {
        boolean ready() {
            return "READY".equals(state) && !hasRecoveryToken && retentionGapReason == null;
        }

        boolean advanceable() {
            return pruned.compareTo(cursor) <= 0 && ready();
        }

        boolean failedWithRetentionGap() {
            return "FAILED".equals(state) && "FACT_CHANGE_LOG_RETENTION_GAP".equals(retentionGapReason);
        }
    }

    /** Relevance window of the just-consumed change-log batch; null start means no relevant event. */
    record DeliveryBounds(LocalDate affectedStart, LocalDate affectedEnd) {
    }

    /** Result of TX1: whether to defer, resolve watermark-only, or advance to a settled target. */
    record SettleOutcome(Kind kind, BigInteger cursor, BigInteger settledTarget) {
        enum Kind { DEFERRED, WATERMARK_ONLY, SETTLED }

        static SettleOutcome deferred(BigInteger cursor) {
            return new SettleOutcome(Kind.DEFERRED, cursor, cursor);
        }

        static SettleOutcome watermarkOnly(BigInteger cursor) {
            return new SettleOutcome(Kind.WATERMARK_ONLY, cursor, cursor);
        }

        static SettleOutcome settled(BigInteger cursor, BigInteger settledTarget) {
            return new SettleOutcome(Kind.SETTLED, cursor, settledTarget);
        }
    }

    /**
     * Fine-grained statements of the two phases, one per protocol step. Implementations bind the shared
     * SQL constants above; production uses an EntityManager, the integration test uses raw JDBC, and
     * unit tests use a mock so the order logic can be verified without a database.
     */
    interface DeliveryPollGateway {
        DeliveryWatermark snapshot();

        String lockPublicationStatus();

        BigInteger maxLogId();

        BigInteger settleHorizon(BigInteger cursor, BigInteger max);

        DeliveryBounds pollBounds(BigInteger cursor, BigInteger target);

        DeliveryWatermark lockWatermark();

        boolean failRetentionGap(BigInteger cursor, BigInteger pruned);

        boolean advance(BigInteger cursor, BigInteger target, LocalDate affectedStart,
                        LocalDate affectedEnd, boolean relevant);
    }

    /** The two transaction phases, each run in its own {@code REQUIRES_NEW} transaction by production. */
    interface DeliveryPollTransactions {
        SettleOutcome settle();

        DeliveryPollResult resolveWatermarkOnly();

        DeliveryPollResult advance(BigInteger cursor, BigInteger settledTarget);
    }
}
