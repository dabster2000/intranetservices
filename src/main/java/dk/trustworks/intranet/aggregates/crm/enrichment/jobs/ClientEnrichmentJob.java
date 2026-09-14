package dk.trustworks.intranet.aggregates.crm.enrichment.jobs;

import dk.trustworks.intranet.aggregates.crm.enrichment.services.ClientEnrichmentService;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The nightly client-enrichment pass: CVR verification, then the sector check, then the
 * logo hunt, each under its own cap.
 *
 * <p>04:00 Copenhagen — after the CRM block (calendar 02:20, TrustLink and Slack 02:40,
 * the person registry 03:00, the retention purge 03:30 UTC) and before the company news
 * at 06:00. CVR runs first because the registry fills in the industry description the
 * sector check reads, and logos last because image generation is the slow part.
 *
 * <p><b>No transaction on this method.</b> The service opens short ones around each write
 * and makes every registry and model call outside them.
 *
 * <p>{@code SKIP} on concurrent execution and the shutdown guard are the pair every
 * scheduled job here carries. The switches, the staging gate and the run lock all live in
 * {@link ClientEnrichmentService#runNightly}, so this class has nothing to decide.
 */
@JBossLog
@ApplicationScoped
public class ClientEnrichmentJob {

    @Inject
    ClientEnrichmentService enrichmentService;

    @Scheduled(cron = "0 0 4 * * ?",
            timeZone = "Europe/Copenhagen",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightly() {
        enrichmentService.runNightly();
    }
}
