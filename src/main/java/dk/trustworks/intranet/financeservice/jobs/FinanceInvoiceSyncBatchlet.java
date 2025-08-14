package dk.trustworks.intranet.financeservice.jobs;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("financeInvoiceSyncBatchlet")
@Dependent
public class FinanceInvoiceSyncBatchlet extends AbstractBatchlet {

    @Inject
    FinanceLoadJob financeLoadJob;

    @Override
    public String process() throws Exception {
        try {
            financeLoadJob.synchronizeInvoices();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("FinanceInvoiceSyncBatchlet failed", e);
            throw e;
        }
    }
}
