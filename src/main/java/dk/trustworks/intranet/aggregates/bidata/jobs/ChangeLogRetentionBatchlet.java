package dk.trustworks.intranet.aggregates.bidata.jobs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
        return em.createNativeQuery(
                "DELETE FROM fact_change_log " +
                "WHERE processed_at IS NOT NULL " +
                "  AND processed_at < DATE_SUB(NOW(), INTERVAL :days DAY) " +
                "LIMIT " + DELETE_CHUNK_SIZE)
                .setParameter("days", retentionDays)
                .executeUpdate();
    }
}
