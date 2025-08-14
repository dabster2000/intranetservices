package dk.trustworks.intranet.expenseservice.services;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("expenseConsumeBatchlet")
@Dependent
public class ExpenseConsumeBatchlet extends AbstractBatchlet {

    @Inject
    ExpenseService expenseService;

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            expenseService.consumeCreate();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("ExpenseConsumeBatchlet failed", e);
            throw e;
        }
    }
}
