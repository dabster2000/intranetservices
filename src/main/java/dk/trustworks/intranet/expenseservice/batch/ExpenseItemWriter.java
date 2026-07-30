package dk.trustworks.intranet.expenseservice.batch;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.utils.UndecodableReceiptException;
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
    // Package-private so the unit test can assert how items were classified.
    int processedCount;
    int successCount;
    int skippedCount;
    int failedCount;
    private long startNs;

    @Override
    public void open(Serializable checkpoint) throws Exception {
        processedCount = 0;
        successCount = 0;
        skippedCount = 0;
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
                 ", skipped=" + skippedCount +
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

        } catch (UndecodableReceiptException e) {
            // The receipt exists but cannot be turned into an attachment e-conomic accepts
            // (HEIC or corrupt — PDFs pass through in ImageProcessor). Park only this item for
            // Accounting attention and keep the rest of the chunk running; count it as a FAILURE
            // so the run cannot read as successful while receipts never reached accounting.
            // If persisting the status fails, let that exception escape as a real batch failure.
            expenseService.updateStatus(expense, ExpenseService.STATUS_UP_FAILED, e.getMessage());
            failedCount++;
            log.errorf("Expense %s cannot reach e-conomic and was parked as UP_FAILED: %s",
                    expense.getUuid(), e.getMessage());

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

        String summary = "processed=" + processedCount +
                ", success=" + successCount +
                ", skipped=" + skippedCount +
                ", failed=" + failedCount +
                ", elapsedMs=" + elapsedMs +
                " (" + elapsedSec + "s)";

        int notUploaded = skippedCount + failedCount;
        if (notUploaded == 0) {
            log.info("ExpenseItemWriter completed: " + summary);
        } else if (notUploaded * 2 >= processedCount) {
            log.error("ExpenseItemWriter completed with MAJORITY FAILURES: " + summary +
                    " — " + notUploaded + " of " + processedCount +
                    " expenses did not reach e-conomic (see UP_FAILED expenses)");
        } else {
            log.warn("ExpenseItemWriter completed with failures: " + summary +
                    " — " + notUploaded + " of " + processedCount +
                    " expenses did not reach e-conomic (see UP_FAILED expenses)");
        }
    }
}
