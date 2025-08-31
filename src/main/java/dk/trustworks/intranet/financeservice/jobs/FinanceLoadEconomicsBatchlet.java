package dk.trustworks.intranet.financeservice.jobs;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("financeLoadEconomicsBatchlet")
@Dependent
@ActivateRequestContext
public class FinanceLoadEconomicsBatchlet extends AbstractBatchlet {

    @Inject
    FinanceLoadJob financeLoadJob;

    @Override
    public String process() throws Exception {
        try {
            financeLoadJob.loadEconomicsData();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("FinanceLoadEconomicsBatchlet failed", e);
            throw e;
        }
    }
}
