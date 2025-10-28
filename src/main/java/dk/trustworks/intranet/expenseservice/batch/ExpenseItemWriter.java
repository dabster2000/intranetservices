package dk.trustworks.intranet.expenseservice.batch;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemWriter;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;
import java.util.List;

/**
 * ItemWriter for expense upload batch job.
 * Processes expenses with configurable throttling (default 10 seconds between uploads).
 * Updates expense status after each processing attempt.
 *
 * Each chunk is processed in a single transaction to prevent timeout issues.
 * Throttling applies between items within a chunk.
 */
@JBossLog
@Named("expenseItemWriter")
@Dependent
public class ExpenseItemWriter implements ItemWriter {

    @Inject
    ExpenseService expenseService;

    @Inject
    @BatchProperty(name = "throttleMs")
    String throttleMsStr;

    private long throttleMs;
    private int processedCount;
    private int successCount;
    private int failedCount;
    private long startNs;

    @Override
    public void open(Serializable checkpoint) throws Exception {
        processedCount = 0;
        successCount = 0;
        failedCount = 0;
        startNs = System.nanoTime();

        // Parse throttle delay (default 10000ms = 10 seconds)
        try {
            throttleMs = (throttleMsStr == null || throttleMsStr.isBlank())
                    ? 10000L
                    : Long.parseLong(throttleMsStr);
        } catch (Exception e) {
            throttleMs = 10000L;
        }

        log.info("ExpenseItemWriter opened: throttleMs=" + throttleMs);
    }

    @Override
    @ActivateRequestContext
    @Transactional
    public void writeItems(List<Object> items) throws Exception {
        log.info("Processing chunk of " + items.size() + " expenses");

        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Expense expense)) {
                continue;
            }

            boolean isLastInBatch = (i == items.size() - 1);
            processExpense(expense, isLastInBatch);
        }

        // Log progress after each chunk
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        log.info("Expense processing progress: " +
                 "processed=" + processedCount +
                 ", success=" + successCount +
                 ", failed=" + failedCount +
                 ", elapsedMs=" + elapsedMs);
    }

    /**
     * Process a single expense with throttling
     */
    private void processExpense(Expense expense, boolean isLastInBatch) {
        try {
            log.info("Processing expense: " + expense.getUuid() +
                     " (amount=" + expense.getAmount() +
                     ", user=" + expense.getUseruuid() + ")");

            // Process the expense (delegates to ExpenseService)
            expenseService.processExpenseItem(expense);

            successCount++;
            log.info("Successfully processed expense: " + expense.getUuid());

        } catch (Exception e) {
            failedCount++;
            log.error("Failed to process expense: " + expense.getUuid(), e);
            // Error handling is done in ExpenseService.processExpenseItem()
            // Status is already updated to UP_FAILED/NO_FILE/NO_USER with error message

        } finally {
            processedCount++;

            // Throttle (sleep) unless this is the last item in the batch
            if (!isLastInBatch && throttleMs > 0) {
                try {
                    Thread.sleep(throttleMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Throttle sleep interrupted");
                }
            }
        }
    }

    @Override
    public Serializable checkpointInfo() throws Exception {
        return null;
    }

    @Override
    public void close() throws Exception {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        long elapsedSec = elapsedMs / 1000;

        log.info("ExpenseItemWriter completed: " +
                 "processed=" + processedCount +
                 ", success=" + successCount +
                 ", failed=" + failedCount +
                 ", elapsedMs=" + elapsedMs +
                 " (" + elapsedSec + "s)");
    }
}
