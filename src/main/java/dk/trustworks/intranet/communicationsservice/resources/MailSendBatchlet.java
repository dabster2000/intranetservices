package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Dependent
@Named("mailSendBatchlet")
@BatchExceptionTracking
public class MailSendBatchlet extends AbstractBatchlet {

    @Inject
    MailResource mailResource;

    @Override
    public String process() throws Exception {
        try {
            mailResource.sendMailJob();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("MailSendBatchlet failed", e);
            throw e;
        }
    }
}
