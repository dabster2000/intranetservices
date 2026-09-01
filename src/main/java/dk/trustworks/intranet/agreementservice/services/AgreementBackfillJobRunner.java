package dk.trustworks.intranet.agreementservice.services;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Single-flight background runner for the agreement-backfill walk (the
 * {@code DocumentMigrationJobRunner} pattern): the walk takes minutes to
 * hours (Graph politeness delay + one AI call per document), far past
 * the ALB's 60 s idle timeout, so the console endpoint starts a job here
 * (409 when one is already running) and polls status.
 *
 * <p>Deliberately NOT a ManagedExecutor: MicroProfile context
 * propagation would capture the submitting HTTP request's CDI context,
 * which is terminated long before the job finishes — every bare Panache
 * read then dies with ContextNotActiveException. A plain thread carries
 * no stale context; the job activates its own request context below.</p>
 */
@JBossLog
@ApplicationScoped
public class AgreementBackfillJobRunner {

    public enum JobType { WALK, WALK_DRY_RUN }

    public record JobStatus(
            JobType job,
            boolean running,
            String runUuid,
            String startedAt,
            String finishedAt,
            Object summary,
            String error) { }

    private final AtomicReference<JobStatus> current = new AtomicReference<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agreement-backfill-job");
        t.setDaemon(true);
        return t;
    });

    public boolean isRunning() {
        JobStatus status = current.get();
        return status != null && status.running();
    }

    /** Start a job; 409 when another one is still running. */
    public synchronized JobStatus start(JobType type, String runUuid, Supplier<Object> work) {
        JobStatus running = current.get();
        if (running != null && running.running()) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"JOB_RUNNING\",\"job\":\"" + running.job() + "\"}")
                    .build());
        }
        JobStatus started = new JobStatus(type, true, runUuid, LocalDateTime.now().toString(), null, null, null);
        current.set(started);
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            boolean activatedHere = !requestContext.isActive();
            if (activatedHere) {
                requestContext.activate();
            }
            try {
                Object summary = work.get();
                current.set(new JobStatus(type, false, runUuid, started.startedAt(),
                        LocalDateTime.now().toString(), summary, null));
                log.infof("Agreement backfill job %s finished (run %s)", type, runUuid);
            } catch (Exception e) {
                log.errorf(e, "Agreement backfill job %s failed (run %s)", type, runUuid);
                current.set(new JobStatus(type, false, runUuid, started.startedAt(),
                        LocalDateTime.now().toString(), null, e.getMessage()));
            } finally {
                if (activatedHere) {
                    requestContext.terminate();
                }
            }
        });
        return started;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Latest job state (the one running, or the last finished one). */
    public JobStatus status() {
        return current.get();
    }
}
