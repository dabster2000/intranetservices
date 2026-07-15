package dk.trustworks.intranet.aggregates.practices.jobs;

import dk.trustworks.intranet.aggregates.practices.services.PracticeCostBasisRefreshService;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/** Queue consumer; this is the only batch component that invokes the cost/basis writer. */
@JBossLog
@Dependent
@Named("practiceCostBasisRefreshBatchlet")
@BatchExceptionTracking
public class PracticeCostBasisRefreshBatchlet extends AbstractBatchlet {
    @Inject PracticeCostBasisRefreshService refreshService;
    @Inject JobContext jobContext;

    @Override
    @ActivateRequestContext
    public String process() {
        PracticeCostBasisRefreshService.ExpectedRequest expected =
                PracticeCostBasisRefreshService.ExpectedRequest.fromJobProperties(
                        jobContext.getProperties());
        PracticeCostBasisRefreshService.Outcome outcome = refreshService.refreshExact(expected);
        log.infof("practice cost/basis refresh request=%s status=%s basis=%s",
                outcome.requestId(), outcome.status(), outcome.basisGenerationId());
        return "COMPLETED";
    }
}
