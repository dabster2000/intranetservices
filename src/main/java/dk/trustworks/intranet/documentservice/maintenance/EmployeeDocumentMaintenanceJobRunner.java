package dk.trustworks.intranet.documentservice.maintenance;

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
 * Single-flight background runner for the employee-document maintenance
 * jobs (categorize, sha256 backfill). A categorize pass over the corpus
 * takes minutes (one OpenAI call per batch, one per excerpt), far past
 * the ALB's idle timeout — so the admin endpoints start a job here (409
 * when one is already running) and poll {@link #status()} until it
 * finishes. One job at a time by design: both jobs write the same rows.
 */
@JBossLog
@ApplicationScoped
public class EmployeeDocumentMaintenanceJobRunner {

    public enum JobType {
        CATEGORIZE, BACKFILL_SHA256
    }

    public record JobStatus(
            JobType job,
            boolean running,
            String startedAt,
            String finishedAt,
            Object summary,
            String error) { }

    private final AtomicReference<JobStatus> current = new AtomicReference<>();

    // Deliberately NOT a ManagedExecutor: MicroProfile context propagation
    // captures the submitting HTTP request's CDI context, which is already
    // terminated by the time a long job runs — every bare Panache read then
    // dies with ContextNotActiveException (first hit in anger by the
    // migration-era prod runs). A plain
    // thread carries no stale context, and the job activates its own fresh
    // request context below.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "employee-document-maintenance-job");
        t.setDaemon(true);
        return t;
    });

    /** Start a job; 409 when another one is still running. */
    public synchronized JobStatus start(JobType type, Supplier<Object> work) {
        JobStatus running = current.get();
        if (running != null && running.running()) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"JOB_RUNNING\",\"job\":\"" + running.job() + "\"}")
                    .build());
        }
        JobStatus started = new JobStatus(type, true, LocalDateTime.now().toString(), null, null, null);
        current.set(started);
        executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            boolean activatedHere = !requestContext.isActive();
            if (activatedHere) requestContext.activate();
            try {
                Object summary = work.get();
                current.set(new JobStatus(type, false, started.startedAt(),
                        LocalDateTime.now().toString(), summary, null));
                log.infof("Maintenance job %s finished", type);
            } catch (Exception e) {
                log.errorf(e, "Maintenance job %s failed", type);
                current.set(new JobStatus(type, false, started.startedAt(),
                        LocalDateTime.now().toString(), null, e.getMessage()));
            } finally {
                if (activatedHere) requestContext.terminate();
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
