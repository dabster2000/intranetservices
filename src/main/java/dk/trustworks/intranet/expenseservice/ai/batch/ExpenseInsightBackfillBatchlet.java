package dk.trustworks.intranet.expenseservice.ai.batch;

import dk.trustworks.intranet.expenseservice.ai.ExpenseAIEnrichmentService;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.Batchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

@JBossLog
@Dependent
@jakarta.inject.Named("expenseInsightBackfillBatchlet")
public class ExpenseInsightBackfillBatchlet implements Batchlet {

    @PersistenceContext
    EntityManager em;

    @Inject
    ExpenseAIEnrichmentService enrichmentService;

    @Inject
    @BatchProperty(name = "limit")
    String limitProp;

    @Inject
    @BatchProperty(name = "sleepMillis")
    String sleepMillisProp;

    private volatile boolean stopRequested = false;

    @Override
    @Transactional
    @ActivateRequestContext
    public String process() throws Exception {
        int limit = parseOrDefault(limitProp, 50);
        long sleepMillis = parseOrDefaultLong(sleepMillisProp, 1500L);

        // JPQL: select eligible expenses
        List<String> ids = em.createQuery(
                        "select e.uuid from Expense e " +
                                "where e.status = 'PROCESSED' " +
                                "and e.vouchernumber > 0 " +
                                "and e.journalnumber is not null " +
                                "and e.accountingyear is not null " +
                                "and not exists (select 1 from ExpenseInsight ei where ei.expenseUuid = e.uuid)",
                        String.class)
                .setMaxResults(limit)
                .getResultList();

        log.infof("Expense insight backfill: found %d pending expenses (processing up to %d)", ids.size(), limit);

        int processed = 0;
        for (String id : ids) {
            if (stopRequested) {
                log.warn("Backfill job stop requested. Exiting early.");
                break;
            }
            try {
                enrichmentService.enrichIfMissing(id);
                processed++;
            } catch (Exception e) {
                log.errorf(e, "Failed enriching expense %s", id);
            }
            // throttle between items
            try { Thread.sleep(sleepMillis); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        log.infof("Expense insight backfill completed. Processed=%d", processed);
        return "COMPLETED";
    }

    @Override
    public void stop() throws Exception {
        stopRequested = true;
    }

    private static int parseOrDefault(String s, int def) {
        try { return s != null ? Integer.parseInt(s) : def; } catch (Exception e) { return def; }
    }

    private static long parseOrDefaultLong(String s, long def) {
        try { return s != null ? Long.parseLong(s) : def; } catch (Exception e) { return def; }
    }
}
