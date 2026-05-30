package dk.trustworks.intranet.expenseservice.jobs;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
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
        LOG.infof("Expense stuck-detection: %d employee-owned NEEDS_ATTENTION rows older than 7 days", stuck);
        if (stuck > 50) LOG.warnf("More than 50 stuck expenses — investigate employee engagement");
    }

    public long countStuck() {
        return Expense.count(
            "state = ?1 and attentionOwner = ?2 and datemodified < ?3",
            ExpenseStateDeriver.NEEDS_ATTENTION,
            ExpenseStateDeriver.OWNER_EMPLOYEE,
            java.time.LocalDate.now().minusDays(7));
    }
}
