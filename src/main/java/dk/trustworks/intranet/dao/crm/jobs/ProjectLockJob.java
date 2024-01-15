package dk.trustworks.intranet.dao.crm.jobs;

import dk.trustworks.intranet.dao.crm.model.Project;
import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ProjectLockJob {

    @Transactional
    @Scheduled(cron="0 0 0 * * ?")
    void relockProjects() {
        Project.update("locked = true");
    }
}
