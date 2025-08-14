package dk.trustworks.intranet.jobs;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("birthdayNotificationBatchlet")
@Dependent
public class BirthdayNotificationBatchlet extends AbstractBatchlet {

    @Inject
    BirthdayNotificationJob job;

    @Override
    public String process() throws Exception {
        try {
            job.sendBirthdayNotifications();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("BirthdayNotificationBatchlet failed", e);
            throw e;
        }
    }
}
