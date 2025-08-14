package dk.trustworks.intranet.dao.crm.jobs;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Named("projectLockBatchlet")
@Dependent
public class ProjectLockBatchlet extends AbstractBatchlet {

    @Inject
    ProjectLockJob job;

    @Override
    public String process() throws Exception {
        try {
            job.relockProjects();
            return "COMPLETED";
        } catch (Exception e) {
            log.error("ProjectLockBatchlet failed", e);
            throw e;
        }
    }
}
