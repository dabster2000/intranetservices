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
        log.infof("ProjectLockJob started: relocking unlocked projects");
        // Only touch rows that are actually unlocked: UPDATE fires the per-row practices
        // dependency trigger on project even when values are unchanged, and with the practices
        // publication live the unrestricted full-table relock ran past the 600s transaction
        // timeout (2026-07-18 reaper-abort incident) while spuriously invalidating the
        // DELIVERY_EVIDENCE source watermark for every project.
        long relocked = Project.update("locked = true where locked = false");
        log.infof("ProjectLockJob completed: %d projects relocked", relocked);
    }
}
