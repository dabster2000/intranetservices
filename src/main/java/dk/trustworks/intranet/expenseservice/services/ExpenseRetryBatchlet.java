package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * Batch job to retry UP_FAILED expenses that already have voucher numbers.
 * This handles the case where voucher creation succeeded but file upload failed.
 * Runs less frequently than the main expense processing job.
 */
@JBossLog
@Named("expenseRetryBatchlet")
@Dependent
@BatchExceptionTracking
public class ExpenseRetryBatchlet extends AbstractBatchlet {

    @Inject
    ExpenseService expenseService;

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            expenseService.retryFailedWithVouchers();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("ExpenseRetryBatchlet failed", e);
            throw e;
        }
    }
}
