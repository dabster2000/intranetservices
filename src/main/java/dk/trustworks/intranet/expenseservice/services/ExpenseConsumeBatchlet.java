package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("expenseConsumeBatchlet")
@Dependent
@BatchExceptionTracking
public class ExpenseConsumeBatchlet extends AbstractBatchlet {

    @Inject
    ExpenseService expenseService;

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        long startTime = System.currentTimeMillis();
        log.infof("ExpenseConsumeBatchlet started — processing validated expenses for upload");
        try {
            expenseService.consumeCreate();
            long durationMs = System.currentTimeMillis() - startTime;
            log.infof("ExpenseConsumeBatchlet completed successfully in %d ms", durationMs);
            return "COMPLETED";
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.errorf(e, "ExpenseConsumeBatchlet failed after %d ms during expense consume/upload — %s: %s",
                    durationMs, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }
}
