package dk.trustworks.intranet.aggregates.crm.news;

import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ClientNewsJob {
    @Inject ClientNewsService service;

    @Scheduled(cron = "0 0 6 * * ?", timeZone = "Europe/Copenhagen",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP, skipExecutionIf = SchedulerShutdownGuard.class)
    void daily() { service.synchronize(); }

    // Recovery after a failed request, missed 06:00 trigger, restart or expired lease.
    // Successful clients are skipped by their durable daily watermark.
    @Scheduled(cron = "0 5 7-23 * * ?", timeZone = "Europe/Copenhagen",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP, skipExecutionIf = SchedulerShutdownGuard.class)
    void recover() { service.synchronize(); }
}
