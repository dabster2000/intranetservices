package dk.trustworks.intranet.batch.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Properties;
import java.util.Set;

/**
 * Admin/ops endpoint to start a known JBeret batch job on demand — the same jobs the
 * {@link dk.trustworks.intranet.batch.BatchScheduler} fires on cron. Useful for validating
 * a batch change without waiting for its scheduled window (e.g. the nightly finance load).
 *
 * <p>Locked to a small allow-list of triggerable jobs and to the {@code system:write} scope;
 * no-ops (409) if the requested job is already running, mirroring the scheduler's guard.
 */
@Tag(name = "system")
@Path("/system/batch")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"system:write"})
@JBossLog
public class BatchTriggerResource {

    /** Jobs that may be started via this endpoint (mirrors the names BatchScheduler manages). */
    private static final Set<String> TRIGGERABLE = Set.of(
            "finance-load-economics", "finance-invoice-sync");

    @Inject
    JobOperator jobOperator;

    @POST
    @Path("/{jobName}/start")
    @Produces(MediaType.TEXT_PLAIN)
    public Response start(@PathParam("jobName") String jobName) {
        if (!TRIGGERABLE.contains(jobName)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Unknown or non-triggerable job: " + jobName
                            + " (allowed: " + TRIGGERABLE + ")").build();
        }
        // Only query running executions if the job has executed at least once; a never-run
        // job is absent from getJobNames() and getRunningExecutions() would throw.
        if (jobOperator.getJobNames().contains(jobName)
                && !jobOperator.getRunningExecutions(jobName).isEmpty()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Job already running: " + jobName).build();
        }
        long executionId = jobOperator.start(jobName, new Properties());
        log.infof("Manually started batch job '%s' (executionId=%d)", jobName, executionId);
        return Response.ok("Started " + jobName + " (executionId=" + executionId + ")").build();
    }
}
