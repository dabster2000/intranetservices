package dk.trustworks.intranet.expenseservice.batch;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import jakarta.batch.api.chunk.ItemReader;
import jakarta.batch.runtime.context.StepContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ItemReader for expense upload batch job.
 * Loads all VALIDATED expenses and reads them one by one for processing.
 * Applies filters:
 * - Amount must be greater than 0
 * - Created more than 2 days ago (to avoid processing very recent submissions)
 */
@JBossLog
@Named("expenseItemReader")
@Dependent
public class ExpenseItemReader implements ItemReader {

    @Inject
    StepContext stepContext;

    private List<Expense> expenses;
    private int index;

    @Override
    @Transactional
    public void open(Serializable checkpoint) throws Exception {
        index = (checkpoint instanceof Integer) ? (Integer) checkpoint : 0;

        // Load all VALIDATED expenses with filters
        LocalDate cutoffDate = LocalDate.now().minusDays(2);

        expenses = Expense.<Expense>stream("status", ExpenseService.STATUS_VALIDATED)
                .filter(e -> e.getAmount() != null && e.getAmount() > 0)
                .filter(e -> e.getDatecreated() != null && e.getDatecreated().isBefore(cutoffDate))
                .collect(Collectors.toList());

        if (expenses.isEmpty()) {
            log.info("No VALIDATED expenses found for processing");
            expenses = new ArrayList<>();
            return;
        }

        log.info("ExpenseItemReader opened: found " + expenses.size() + " validated expenses to process");
    }

    @Override
    public Object readItem() throws Exception {
        if (expenses == null || index >= expenses.size()) {
            return null; // No more items
        }

        Expense expense = expenses.get(index++);
        log.debug("Reading expense " + index + "/" + expenses.size() + ": " + expense.getUuid());

        return expense;
    }

    @Override
    public Serializable checkpointInfo() throws Exception {
        return index;
    }

    @Override
    public void close() throws Exception {
        log.info("ExpenseItemReader closed: processed " + index + " expenses");
    }
}
