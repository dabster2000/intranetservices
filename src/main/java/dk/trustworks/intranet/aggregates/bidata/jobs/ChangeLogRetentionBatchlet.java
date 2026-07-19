package dk.trustworks.intranet.aggregates.bidata.jobs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigInteger;
import java.util.List;

/**
 * Daily reaper for {@code fact_change_log} — prunes processed entries older
 * than {@link #retentionDays} so the queue table does not grow unbounded.
 * Runs at 02:15 UTC; idempotent and self-recovering.
 */
@JBossLog
@ApplicationScoped
public class ChangeLogRetentionBatchlet {

    static final int DELETE_CHUNK_SIZE = 100_000;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "factChangeLog.retention.days", defaultValue = "30")
    int retentionDays;

    @Scheduled(cron = "0 15 2 * * ?", identity = "fact-change-log-retention")
    public void scheduledRun() {
        try {
            long deleted = run();
            log.infof("ChangeLogRetentionBatchlet: deleted %d rows older than %d days",
                    deleted, retentionDays);
        } catch (RuntimeException e) {
            log.errorf(e, "ChangeLogRetentionBatchlet failed");
        }
    }

    long run() {
        long totalDeleted = 0L;
        int deletedInChunk;
        do {
            deletedInChunk = deleteOneChunkInOwnTransaction();
            totalDeleted += deletedInChunk;
        } while (deletedInChunk == DELETE_CHUNK_SIZE);
        return totalDeleted;
    }

    // Each chunk runs in its own transaction so a mid-loop failure does not
    // roll back work already committed. Test seam: package-private + non-final
    // so Mockito.spy() can substitute a direct call to deleteOneChunk() and
    // skip the JTA round-trip in unit tests.
    int deleteOneChunkInOwnTransaction() {
        return QuarkusTransaction.requiringNew().call(this::deleteOneChunk);
    }

    int deleteOneChunk() {
        @SuppressWarnings("unchecked")
        List<Object> candidateIds = em.createNativeQuery(
                "SELECT id FROM fact_change_log " +
                "WHERE processed_at IS NOT NULL " +
                "  AND processed_at < DATE_SUB(UTC_TIMESTAMP(6), INTERVAL :days DAY) " +
                "ORDER BY id FOR UPDATE")
                .setParameter("days", retentionDays)
                .setMaxResults(DELETE_CHUNK_SIZE)
                .getResultList();
        if (candidateIds.isEmpty()) return 0;

        BigInteger highestActualDeletedId = candidateIds.stream()
                .map(ChangeLogRetentionBatchlet::integer)
                .max(BigInteger::compareTo)
                .orElseThrow();

        int watermarkUpdated = em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET last_pruned_fact_change_log_id=GREATEST(last_pruned_fact_change_log_id,:highest),
                    source_state=CASE WHEN last_fact_change_log_id < :highest THEN 'FAILED' ELSE source_state END,
                    attempt_token=CASE WHEN last_fact_change_log_id < :highest THEN NULL ELSE attempt_token END,
                    started_at=CASE WHEN last_fact_change_log_id < :highest THEN NULL ELSE started_at END,
                    completed_at=CASE WHEN last_fact_change_log_id < :highest THEN UTC_TIMESTAMP(6) ELSE completed_at END,
                    safe_reason=CASE WHEN last_fact_change_log_id < :highest
                                     THEN 'FACT_CHANGE_LOG_RETENTION_GAP' ELSE safe_reason END,
                    retention_gap_reason=CASE WHEN last_fact_change_log_id < :highest
                                              THEN 'FACT_CHANGE_LOG_RETENTION_GAP' ELSE retention_gap_reason END,
                    last_observed_at=UTC_TIMESTAMP(6),
                    optimistic_version=optimistic_version+1
                WHERE source_name='DELIVERY_EVIDENCE'
                """).setParameter("highest", highestActualDeletedId).executeUpdate();
        if (watermarkUpdated != 1) {
            throw new IllegalStateException("DELIVERY_EVIDENCE_WATERMARK_MISSING");
        }

        return em.createNativeQuery(
                "DELETE FROM fact_change_log " +
                "WHERE id <= :highest " +
                "  AND processed_at IS NOT NULL " +
                "  AND processed_at < DATE_SUB(UTC_TIMESTAMP(6), INTERVAL :days DAY)")
                .setParameter("highest", highestActualDeletedId)
                .setParameter("days", retentionDays)
                .executeUpdate();
    }

    private static BigInteger integer(Object value) {
        return value instanceof BigInteger integer ? integer : new BigInteger(value.toString());
    }
}
