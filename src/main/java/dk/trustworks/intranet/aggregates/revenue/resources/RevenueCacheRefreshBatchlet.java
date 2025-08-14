package dk.trustworks.intranet.aggregates.revenue.resources;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@Deprecated
@JBossLog
@Named("revenueCacheRefreshBatchlet")
@Dependent
public class RevenueCacheRefreshBatchlet extends AbstractBatchlet {

    @Inject
    RevenueResource revenueResource;

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            //revenueResource.refreshCaches();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("RevenueCacheRefreshBatchlet failed", e);
            throw e;
        }
    }
}
