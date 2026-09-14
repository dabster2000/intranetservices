package dk.trustworks.intranet.aggregates.crm.person.jobs;

import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Rebuilds {@code account_person} for every client, nightly at 03:00, and catches up when it
 * has never run (spec §3.1).
 *
 * <h2>Why 03:00</h2>
 * After all three feeds that produce people: the calendar sync at 02:20, the account Slack
 * sync at 02:25, and TrustLink and the Slack source channels at 02:40. A rebuild that ran
 * before them would spend the day showing yesterday's people, and the 02:40 slot is already
 * triple-booked, so a fresh minute was picked rather than a fourth job fighting the same
 * connection pool.
 *
 * <p>No {@code timeZone}, matching the CRM neighbours that also omit it — the pass is
 * idempotent and convergent at any hour, so which midnight it belongs to is not a decision
 * this job has to take.
 *
 * <h2>{@code recover()} replaces the spec's startup hook</h2>
 * Spec §3.1 asks for a rebuild "once at startup when the build state says it has never run",
 * so the tab is not empty until 03:00 on the day of the deploy. <b>This repository does not
 * put a database probe in a {@code StartupEvent} observer</b>: a boot-time query on a worker
 * thread races the main startup thread's Hibernate session and can abort startup — the
 * 2026-06-20 incident — which is why three finance health checks and
 * {@code ConsultantProfilePrewarmBatchlet} each carry javadoc refusing one.
 *
 * <p>The in-package idiom is a second, frequent cron with a durable watermark that makes it a
 * no-op once the work is done: {@code ClientNewsJob.recover()} at {@code 0 5 7-23 * * ?}. This
 * is that, with {@code account_person_build_state} as the watermark. At most 20 minutes pass
 * between a deploy and a built registry, and once {@code last_run_at} is set the hourly tick
 * costs one indexed primary-key read.
 *
 * <h2>The two annotations every job here carries</h2>
 * {@code concurrentExecution = SKIP} because a pass that overlapped the previous one would
 * fight it for the same upserts, and {@code skipExecutionIf = SchedulerShutdownGuard.class}
 * because a pass that starts during a deployment is killed halfway through — and because
 * {@code SchedulerShutdownGuardCoverageTest} is an ArchUnit gate that fails the build for any
 * live {@code @Scheduled} method without it.
 *
 * <h2>No transaction on either method</h2>
 * Deliberate, as on every other CRM job. The service opens a short transaction per client, so
 * one client's failure costs that client; a transaction opened here would hold one pooled
 * connection across all ~300 of them and make the pass all-or-nothing.
 *
 * <h2>The kill switch is not read here</h2>
 * {@code dk.trustworks.crm.person.rebuild.enabled} is checked inside
 * {@link AccountPersonService#rebuildAll()}, which is where every CRM lane checks its flag.
 * A job body that returned early would leave the "switched off" line unlogged on the recovery
 * path and would have to repeat the check in two places.
 */
@JBossLog
@ApplicationScoped
public class AccountPersonRebuildJob {

    @Inject
    AccountPersonService personService;

    @Scheduled(cron = "0 0 3 * * ?",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightly() {
        log.info("Account person registry rebuild starting");
        personService.rebuildAll();
    }

    /**
     * Builds the registry once, hourly, for as long as it has never been built.
     *
     * <p>Minute 15 so the tick does not land on the hour with everything else, and 04:00
     * onwards so it never races the 03:00 pass it exists to stand in for.
     *
     * <p>{@code hasNeverRun()} is true for BOTH a missing build-state row and a seeded row
     * with a null {@code last_run_at}. A pass that ran and failed every client still moved
     * {@code last_run_at}, so this stops re-running a rebuild that is failing — the widening
     * gap between {@code last_run_at} and {@code last_success_at} is the alarm for that, not
     * an hourly retry.
     */
    @Scheduled(cron = "0 15 4-23 * * ?",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void recover() {
        if (!personService.hasNeverRun()) {
            return;
        }
        log.info("Account person registry has never been built — recovering");
        personService.rebuildAll();
    }
}
