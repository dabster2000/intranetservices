package dk.trustworks.intranet.scheduling;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Stops scheduled work at the <em>start</em> of the shutdown sequence, so a
 * tick can never land after the persistence unit is gone.
 * <p>
 * <b>The defect.</b> Quarkus' {@code SimpleScheduler.stop()} is annotated
 * {@code @PreDestroy}, and its whole body is
 * {@code scheduledExecutor.shutdownNow()} — it never clears the scheduler's
 * own {@code running} flag. {@code @PreDestroy} runs at CDI container
 * destruction, which is the <em>last</em> phase of shutdown, while
 * {@code HibernateOrmRecorder} closes the {@code EntityManagerFactory} in an
 * earlier {@code ShutdownContext} task. Between those two points the
 * scheduler is still dispatching: every trigger that ticks in that window and
 * touches the database dies with
 * {@code IllegalArgumentException: Unable to find an EntityManagerFactory for
 * persistence unit '<default>'}, which {@code StatusEmitterInvoker} logs at
 * ERROR and then swallows. The tick is dropped, not retried. In production
 * this reliably hits the 1-minute triggers during a deploy drain.
 * <p>
 * <b>The fix, in two layers.</b>
 * <ol>
 *   <li>{@link #onShutdown} observes {@link ShutdownEvent} — which Quarkus
 *       guarantees fires <em>before</em> the persistence unit closes, because
 *       {@code ShutdownContext} tasks run in reverse registration order and
 *       the ORM shutdown task is registered first ("users will have access to
 *       the ORM stuff in their listeners") — and calls {@link Scheduler#pause()}.
 *       {@code SimpleScheduler.checkTriggers()} early-returns while paused, so
 *       this stops dispatch for <em>every</em> scheduled job at once, with no
 *       per-job code. That is the layer that actually closes the window.</li>
 *   <li>This class is also a {@link Scheduled.SkipPredicate}. A job declaring
 *       {@code skipExecutionIf = SchedulerShutdownGuard.class} is additionally
 *       checked on the worker thread immediately before its method runs, which
 *       closes the residual race where a tick was already handed to a Vert.x
 *       worker microseconds before the pause. {@code OffloadingInvoker} wraps
 *       {@code SkipPredicateInvoker}, so the predicate genuinely runs after the
 *       offload rather than before it.</li>
 * </ol>
 * <p>
 * <b>Why this must stay a plain CDI bean.</b> The scheduler resolves a
 * {@code skipExecutionIf} class via
 * {@code SchedulerUtils.instantiateBeanOrClass}, which does
 * {@code Arc.container().select(type, Any)} and falls back to
 * {@code type.getConstructor().newInstance()} when that is <em>unsatisfied</em>.
 * If this class ever stopped being a resolvable, unambiguous bean, the
 * scheduler would quietly construct a second instance whose flag nobody sets,
 * and the guard would silently do nothing while still appearing to be wired
 * up. Do not add a subclass, a producer, or an {@code @Typed} restriction.
 * <p>
 * Nothing here swallows the underlying failure: skips are logged at WARN
 * (the framework only logs them at DEBUG), so a dropped tick stays visible.
 */
@JBossLog
@ApplicationScoped
public class SchedulerShutdownGuard implements Scheduled.SkipPredicate {

    /**
     * Written by the shutdown observer, read on Vert.x worker threads —
     * hence volatile. One-way: nothing ever clears it in production.
     */
    private volatile boolean shuttingDown;

    @Inject
    Scheduler scheduler;

    /**
     * Fires at the start of the shutdown sequence, while the persistence unit
     * is still open. No {@code @Priority} is needed for correctness — plain
     * {@link ShutdownEvent} already precedes the ORM shutdown task — and this
     * is the codebase's only ShutdownEvent observer, so there is no ordering
     * to negotiate with a peer.
     */
    void onShutdown(@Observes ShutdownEvent event) {
        // Set the flag FIRST: even if pause() fails, in-flight ticks must
        // still see that we are going down.
        shuttingDown = true;
        try {
            scheduler.pause();
            log.info("Shutdown started — scheduler paused; no further scheduled ticks will be dispatched");
        } catch (RuntimeException e) {
            // SimpleScheduler.pause() throws when the scheduler was never
            // started (scheduler disabled, HALTED start mode, some test
            // contexts). That is survivable — the flag above still guards
            // every job that declares this predicate — but it must never
            // propagate, because an exception here would abort the rest of
            // the shutdown sequence. Deliberately narrow: only pause() is
            // inside the try.
            log.warnf("Could not pause the scheduler during shutdown (%s: %s) — "
                            + "relying on the shutdown flag alone",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Evaluated on the worker thread just before the scheduled method runs.
     */
    @Override
    public boolean test(ScheduledExecution execution) {
        if (!shuttingDown) {
            return false;
        }
        // WARN, not DEBUG: SkipPredicateInvoker's own skip log is DEBUG, so at
        // production log levels a skipped tick would otherwise be invisible.
        // We are deliberately not hiding dropped work.
        log.warnf("Shutdown in progress — skipping scheduled tick for trigger %s",
                execution.getTrigger().getId());
        return true;
    }

    /** True once {@link ShutdownEvent} has been observed. */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Test-only: clears the one-way shutdown flag so a single test instance
     * can exercise both sides of the transition. Package-private on purpose —
     * production code must never be able to un-shutdown the scheduler.
     */
    void resetForTest() {
        shuttingDown = false;
    }
}
