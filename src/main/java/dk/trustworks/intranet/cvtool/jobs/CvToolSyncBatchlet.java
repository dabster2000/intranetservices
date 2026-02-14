package dk.trustworks.intranet.cvtool.jobs;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.cvtool.service.CvToolSyncService;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * Jakarta Batch batchlet for nightly CV Tool synchronization.
 * Fetches base CVs from the external CV Tool API and stores them locally.
 */
@JBossLog
@Named("cvToolSyncBatchlet")
@Dependent
@BatchExceptionTracking
public class CvToolSyncBatchlet extends AbstractBatchlet {

    @Inject
    CvToolSyncService syncService;

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            return syncService.syncAllBaseCvs();
        } catch (Exception e) {
            log.error("CvToolSyncBatchlet failed", e);
            throw e;
        }
    }
}
