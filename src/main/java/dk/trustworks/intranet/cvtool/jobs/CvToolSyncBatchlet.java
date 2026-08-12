package dk.trustworks.intranet.cvtool.jobs;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.cvtool.service.CvToolRetryExecutor;
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
            // Name the deepest cause on the message line. The stack trace does carry
            // it, but a socket read timeout arrives as CvToolSyncException →
            // ProcessingException → SocketTimeoutException, so the fact that actually
            // identifies the failure is the second "Caused by:" — the part that gets
            // scrolled past or truncated in CloudWatch.
            log.errorf(e, "CvToolSyncBatchlet failed: %s", CvToolRetryExecutor.rootCause(e));
            throw e;
        }
    }
}
