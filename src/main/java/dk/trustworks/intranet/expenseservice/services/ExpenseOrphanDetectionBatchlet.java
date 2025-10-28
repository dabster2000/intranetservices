package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.expenseservice.model.Expense;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Batch job to detect and mark orphaned vouchers.
 * An orphaned voucher is one where we have a voucher number in our database
 * but the voucher doesn't actually exist in e-conomics.
 *
 * This job runs hourly to proactively identify these cases so they can be
 * handled with the smart idempotency strategy on retry.
 *
 * The detection process:
 * 1. Find expenses with voucher references (any status except DELETED)
 * 2. Batch verify they exist in e-conomics
 * 3. Mark orphaned vouchers for special handling
 * 4. Log findings for monitoring
 */
@JBossLog
@Dependent
@Named("expenseOrphanDetectionBatchlet")
@BatchExceptionTracking
public class ExpenseOrphanDetectionBatchlet extends AbstractBatchlet {

    @Inject
    EconomicsService economicsService;

    @Inject
    ExpenseService expenseService;

    private static final int BATCH_SIZE = 10;
    private static final int MAX_EXPENSES_TO_CHECK = 100;

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            log.info("Starting orphaned voucher detection job");

            AtomicInteger totalChecked = new AtomicInteger(0);
            AtomicInteger orphansFound = new AtomicInteger(0);
            AtomicInteger errorsFound = new AtomicInteger(0);

            // Find expenses with voucher numbers that might be orphaned
            // Check multiple statuses as vouchers can become orphaned at any stage
            List<Expense> expensesToCheck = Expense.<Expense>find(
                "vouchernumber > 0 AND journalnumber IS NOT NULL AND accountingyear IS NOT NULL " +
                "AND status IN (?1, ?2, ?3, ?4) " +
                "AND (isOrphaned = false OR isOrphaned IS NULL) " +
                "ORDER BY datemodified DESC",
                ExpenseService.STATUS_VOUCHER_CREATED,
                ExpenseService.STATUS_UPLOADED,
                ExpenseService.STATUS_UP_FAILED,
                ExpenseService.STATUS_VERIFIED_UNBOOKED)
                .page(0, MAX_EXPENSES_TO_CHECK)
                .list();

            log.infof("Found %d expenses with voucher references to verify", expensesToCheck.size());

            // Process in batches to avoid overwhelming e-conomics API
            for (int i = 0; i < expensesToCheck.size(); i += BATCH_SIZE) {
                List<Expense> batch = expensesToCheck.subList(i,
                    Math.min(i + BATCH_SIZE, expensesToCheck.size()));

                for (Expense expense : batch) {
                    try {
                        boolean voucherExists = economicsService.verifyVoucherExists(expense);
                        totalChecked.incrementAndGet();

                        if (!voucherExists) {
                            log.warnf("Orphaned voucher detected: expense=%s, voucher=%d, journal=%d, year=%s",
                                expense.getUuid(), expense.getVouchernumber(),
                                expense.getJournalnumber(), expense.getAccountingyear());

                            // Mark as orphaned
                            expense.markAsOrphaned();
                            expense.setLastRetryAt(LocalDateTime.now());

                            // Update database
                            Expense.update("isOrphaned = ?1, lastRetryAt = ?2 WHERE uuid = ?3",
                                true, LocalDateTime.now(), expense.getUuid());

                            orphansFound.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.errorf(e, "Error checking voucher existence for expense %s", expense.getUuid());
                        errorsFound.incrementAndGet();
                    }
                }

                // Brief pause between batches to avoid API rate limiting
                if (i + BATCH_SIZE < expensesToCheck.size()) {
                    Thread.sleep(1000);
                }
            }

            String summary = String.format("Orphan detection completed: checked=%d, orphans=%d, errors=%d",
                totalChecked.get(), orphansFound.get(), errorsFound.get());
            log.info(summary);

            // Log warning if high orphan rate detected
            if (orphansFound.get() > 5) {
                log.warnf("High orphan rate detected: %d orphaned vouchers found. " +
                    "This may indicate an issue with e-conomics integration.", orphansFound.get());
            }

            return "COMPLETED - " + summary;
        } catch (Exception e) {
            log.error("ExpenseOrphanDetectionBatchlet failed", e);
            throw e;
        }
    }
}