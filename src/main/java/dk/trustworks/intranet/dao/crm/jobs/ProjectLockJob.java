package dk.trustworks.intranet.dao.crm.jobs;

import dk.trustworks.intranet.dao.crm.model.Project;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@JBossLog
@ApplicationScoped
public class ProjectLockJob {

    @Transactional
    //@Scheduled(cron="0 0 0 * * ?") // Disabled: replaced by JBeret job 'project-lock' via BatchScheduler
    void relockProjects() {
        log.infof("ProjectLockJob started: relocking all projects");
        long count = Project.count();
        Project.update("locked = true");
        log.infof("ProjectLockJob completed: %d projects relocked", count);
    }
}
