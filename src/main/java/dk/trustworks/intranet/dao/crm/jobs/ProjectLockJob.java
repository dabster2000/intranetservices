package dk.trustworks.intranet.dao.crm.jobs;

import dk.trustworks.intranet.dao.crm.model.Project;
import io.quarkus.scheduler.Scheduled;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped
public class ProjectLockJob {

    @Transactional
    @Scheduled(cron="0 0 0 * * ?")
    void relockProjects() {
        Project.update("locked = true");
    }
}
