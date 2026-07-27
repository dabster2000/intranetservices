package dk.trustworks.intranet.documentservice.migration.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Single-flight background runner for the migration jobs. The crawl and
 * copy runs take minutes to low hours (Graph politeness delay), far past
 * the ALB's idle timeout — so the admin-card endpoints start a job here
 * (409 when one is already running) and poll {@link #status()} until it
 * finishes. One job at a time by design: the M1→M5 steps are strictly
 * sequential in the runbook.
 */
@JBossLog
@ApplicationScoped
public class DocumentMigrationJobRunner {

    public enum JobType { CRAWL, MATCH, COPY_DRY_RUN, COPY, CATEGORIZE, VERIFY }

    public record JobStatus(
            JobType job,
            boolean running,
            String startedAt,
            String finishedAt,
            Object summary,
            String error) { }

    private final AtomicReference<JobStatus> current = new AtomicReference<>();

    @Inject
    ManagedExecutor executor;

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
            try {
                Object summary = work.get();
                current.set(new JobStatus(type, false, started.startedAt(),
                        LocalDateTime.now().toString(), summary, null));
                log.infof("Migration job %s finished", type);
            } catch (Exception e) {
                log.errorf(e, "Migration job %s failed", type);
                current.set(new JobStatus(type, false, started.startedAt(),
                        LocalDateTime.now().toString(), null, e.getMessage()));
            }
        });
        return started;
    }

    /** Latest job state (the one running, or the last finished one). */
    public JobStatus status() {
        return current.get();
    }
}
