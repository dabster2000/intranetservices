package dk.trustworks.intranet.recruitmentservice.services;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.jbosslog.JBossLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Method B availability loop's after-commit offload (plan §12.2's
 * "on the executor" step). Deliberately NOT a {@code ManagedExecutor}:
 * MicroProfile context propagation captures the submitting thread's JTA
 * and CDI request contexts, and a task submitted from an after-commit
 * hook then runs with the COMPLETED transaction still associated —
 * {@code QuarkusTransaction.isActive()} answers true and the §P9 M1
 * guard in {@code AvailabilityExtractionService} fires on every call
 * (production finding F10, 2026-08-14). A plain daemon thread propagates
 * nothing; the task activates a fresh request context and opens its own
 * {@code QuarkusTransaction} blocks where it needs the database.
 */
@JBossLog
@ApplicationScoped
public class SchedulingAsyncRunner {

    @Inject
    TransactionSynchronizationRegistry txSyncRegistry;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "methodb-availability");
        t.setDaemon(true);
        return t;
    });

    /**
     * Run {@code task} after the current transaction commits — or right
     * away when no transaction is active (direct callers, tests). The
     * task gets a clean thread: no propagated transaction, a fresh
     * request context.
     */
    public void submitAfterCommit(Runnable task) {
        try {
            txSyncRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        submit(task);
                    }
                }
            });
        } catch (Exception e) {
            // No active transaction — run now.
            submit(task);
        }
    }

    private void submit(Runnable task) {
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            boolean activatedHere = !requestContext.isActive();
            if (activatedHere) {
                requestContext.activate();
            }
            try {
                task.run();
            } catch (Exception e) {
                log.error("Method B availability task failed", e);
            } finally {
                if (activatedHere) {
                    requestContext.terminate();
                }
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
