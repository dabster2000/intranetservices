package dk.trustworks.intranet.jobs;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("slackUserSyncBatchlet")
@Dependent
public class SlackUserSyncBatchlet extends AbstractBatchlet {

    @Inject
    SlackUserSyncJob job;

    @Override
    public String process() throws Exception {
        try {
            job.syncSlackUserIds();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("SlackUserSyncBatchlet failed", e);
            throw e;
        }
    }
}
