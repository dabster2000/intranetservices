package dk.trustworks.intranet.expenseservice.jobs;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ExpenseStuckDetectionBatchlet {

    private static final Logger LOG = Logger.getLogger(ExpenseStuckDetectionBatchlet.class);

    @Scheduled(cron = "0 0 6 * * ?", identity = "expense-stuck-detection")
    @Transactional
    public void runNightly() {
        long stuck = countStuck();
        LOG.infof("Expense stuck-detection: %d rows in NEEDS_FIX/NEEDS_JUSTIFICATION older than 7 days", stuck);
        if (stuck > 50) LOG.warnf("More than 50 stuck expenses — investigate employee engagement");
    }

    public long countStuck() {
        return Expense.count(
            "reviewState in ?1 and datemodified < ?2",
            java.util.List.of("NEEDS_FIX", "NEEDS_JUSTIFICATION"),
            java.time.LocalDate.now().minusDays(7));
    }
}
