package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Dependent
@Named("mailSendBatchlet")
public class MailSendBatchlet extends MonitoredBatchlet {

    @Inject
    MailResource mailResource;

    @Override
    protected String doProcess() throws Exception {
        try {
            mailResource.sendMailJob();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("MailSendBatchlet failed", e);
            throw e;
        }
    }

    @Override
    protected void onFinally(long executionId, String jobName) {
        // Optional cleanup
        log.info("Cleaning up after job execution");
    }
}
