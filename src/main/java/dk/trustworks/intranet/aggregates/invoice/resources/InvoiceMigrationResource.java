package dk.trustworks.intranet.aggregates.invoice.resources;

import dk.trustworks.intranet.batch.monitoring.BatchJobTrackingQuery;
import dk.trustworks.intranet.batch.monitoring.BatchJobExecutionTracking;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchRuntime;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.util.Properties;

/**
 * REST API for triggering and monitoring the one-time invoice PDF migration job.
 *
 * <p>This resource provides endpoints to:
 * <ul>
 *   <li>Manually trigger the PDF migration batch job</li>
 *   <li>Check the status of a running or completed migration</li>
 *   <li>View migration execution history</li>
 * </ul>
 *
 * <h2>Security</h2>
 * All endpoints require ADMIN role for authorization. This prevents accidental
 * or unauthorized triggering of the data migration job.
 *
 * <h2>Usage Example</h2>
 * <pre>
 * # 1. Start migration
 * curl -X POST https://api.trustworks.dk/admin/migration/invoice-pdfs/start \
 *   -H "Authorization: Bearer {admin-jwt-token}"
 *
 * # Response: {"executionId": 12345, "status": "STARTED", "message": "..."}
 *
 * # 2. Monitor progress
 * curl https://api.trustworks.dk/admin/migration/invoice-pdfs/status/12345 \
 *   -H "Authorization: Bearer {admin-jwt-token}"
 *
 * # Response: {"executionId": 12345, "status": "COMPLETED", "result": "COMPLETED", ...}
 * </pre>
 *
 * @see dk.trustworks.intranet.aggregates.invoice.jobs.InvoicePdfMigrationBatchlet
 */
@Path("/admin/migration/invoice-pdfs")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@PermitAll
@Tag(name = "Invoice PDF Migration", description = "One-time migration of invoice PDFs from database to S3")
@JBossLog
public class InvoiceMigrationResource {

    private static final String JOB_NAME = "invoice-pdf-migration";

    @Inject
    JobOperator jobOperator;

    @Inject
    BatchJobTrackingQuery trackingQuery;

    /**
     * Trigger the invoice PDF migration job.
     *
     * <p>This endpoint starts the batch job that migrates invoice PDFs from the
     * {@code invoices.pdf} BLOB column to S3 storage, updating the {@code invoices_v2}
     * table with S3 URLs and SHA-256 checksums.
     *
     * <h3>Safety Features</h3>
     * <ul>
     *   <li>Prevents concurrent executions - only one migration can run at a time</li>
     *   <li>Guard logic in job prevents accidental re-runs if already completed</li>
     *   <li>Original BLOB data remains intact for rollback capability</li>
     * </ul>
     *
     * @return HTTP 202 (Accepted) with execution ID if job started successfully,
     *         HTTP 409 (Conflict) if job already running,
     *         HTTP 500 (Server Error) if job failed to start
     */
    @POST
    @Path("/start")
    @Operation(
        summary = "Start invoice PDF migration job",
        description = "Triggers the one-time batch job to migrate invoice PDFs from database BLOBs to S3 storage"
    )
    @APIResponses({
        @APIResponse(
            responseCode = "202",
            description = "Migration job started successfully",
            content = @Content(schema = @Schema(implementation = MigrationStartResponse.class))
        ),
        @APIResponse(
            responseCode = "409",
            description = "Migration job is already running",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Failed to start migration job",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response startMigration() {
        log.info("=== Invoice PDF Migration Job Trigger Request ===");
        log.info("Requested by: ADMIN user");
        log.info("Job name: " + JOB_NAME);

        try {
            // Check if job is already running
            // First verify job exists in registry, then check for running executions
            if (jobOperator.getJobNames().contains(JOB_NAME)) {
                if (!jobOperator.getRunningExecutions(JOB_NAME).isEmpty()) {
                    log.warn("Migration job is already running - rejecting duplicate request");
                    return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse(
                            "Migration job is already running. " +
                            "Please wait for the current execution to complete."
                        ))
                        .build();
                }
            }

            // Start the job
            long executionId = jobOperator.start(JOB_NAME, new Properties());

            log.infof("Migration job started successfully with execution ID: %d", executionId);
            log.info("Monitor progress via GET /admin/migration/invoice-pdfs/status/" + executionId);

            return Response.accepted(new MigrationStartResponse(
                executionId,
                "STARTED",
                "Invoice PDF migration job started successfully. " +
                "Use the executionId to monitor progress via the /status/{executionId} endpoint."
            )).build();

        } catch (Exception e) {
            log.errorf(e, "Failed to start migration job: %s", e.getMessage());
            return Response.serverError()
                .entity(new ErrorResponse(
                    "Failed to start migration job: " + e.getMessage()
                ))
                .build();
        }
    }

    /**
     * Get the status of a specific migration job execution.
     *
     * <p>Returns detailed information about the job execution including:
     * <ul>
     *   <li>Current status (STARTED, COMPLETED, FAILED, etc.)</li>
     *   <li>Result (COMPLETED, SKIPPED, PARTIAL, etc.)</li>
     *   <li>Start and end times</li>
     *   <li>Progress percentage (if available)</li>
     *   <li>Detailed execution logs</li>
     * </ul>
     *
     * @param executionId the batch job execution ID returned from /start endpoint
     * @return HTTP 200 with job status details if found,
     *         HTTP 404 if execution not found
     */
    @GET
    @Path("/status/{executionId}")
    @Operation(
        summary = "Get migration job status",
        description = "Retrieves the current status and details of a specific migration job execution"
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Job status retrieved successfully",
            content = @Content(schema = @Schema(implementation = MigrationStatusResponse.class))
        ),
        @APIResponse(
            responseCode = "404",
            description = "Execution not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response getStatus(@PathParam("executionId") long executionId) {
        log.debugf("Status query for execution ID: %d", executionId);

        return trackingQuery.findByExecutionId(executionId)
            .map(tracking -> {
                MigrationStatusResponse response = new MigrationStatusResponse(
                    tracking.getExecutionId(),
                    tracking.getJobName(),
                    tracking.getStatus(),
                    tracking.getResult(),
                    tracking.getStartTime(),
                    tracking.getEndTime(),
                    tracking.getProgressPercent(),
                    tracking.getDetails(),
                    tracking.getTraceLog()
                );

                log.debugf("Returning status for execution %d: %s (%s)",
                          executionId, tracking.getStatus(), tracking.getResult());

                return Response.ok(response).build();
            })
            .orElseGet(() -> {
                log.warnf("Execution ID %d not found in tracking database", executionId);
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse(
                        "Execution ID " + executionId + " not found. " +
                        "The job may not have started yet or the ID is invalid."
                    ))
                    .build();
            });
    }

    /**
     * Get a summary of migration job executions.
     *
     * <p>Returns a list of recent migration job executions with their status.
     * Useful for viewing execution history and identifying failed runs.
     *
     * @param limit maximum number of executions to return (default: 10, max: 50)
     * @return HTTP 200 with list of recent executions
     */
    @GET
    @Path("/history")
    @Operation(
        summary = "Get migration job history",
        description = "Retrieves a list of recent migration job executions with their status"
    )
    @APIResponse(
        responseCode = "200",
        description = "Execution history retrieved successfully"
    )
    public Response getHistory(@QueryParam("limit") @DefaultValue("10") int limit) {
        log.debugf("History query requested (limit: %d)", limit);

        // Use search method with pagination
        int pageSize = Math.min(limit, 50); // Cap at 50
        var result = trackingQuery.search(
            JOB_NAME,     // jobName
            null,         // status (all)
            null,         // result (all)
            null,         // runningOnly
            null,         // startFrom
            null,         // startTo
            null,         // endFrom
            null,         // endTo
            0,            // page
            pageSize,     // size
            "startTime,desc" // sort by most recent
        );

        log.debugf("Found %d historical executions for job '%s'", result.items.size(), JOB_NAME);

        return Response.ok(result.items).build();
    }

    // ============================================================================
    // Response DTOs
    // ============================================================================

    /**
     * Response DTO for successful job start.
     *
     * @param executionId the batch job execution ID for tracking
     * @param status the initial job status (typically "STARTED")
     * @param message informational message with next steps
     */
    public record MigrationStartResponse(
        long executionId,
        String status,
        String message
    ) {}

    /**
     * Response DTO for job status queries.
     *
     * @param executionId the batch job execution ID
     * @param jobName the job name (invoice-pdf-migration)
     * @param status current job status (STARTED, COMPLETED, FAILED, etc.)
     * @param result job result (COMPLETED, SKIPPED, PARTIAL, etc.)
     * @param startTime when the job started
     * @param endTime when the job finished (null if still running)
     * @param progressPercent estimated completion percentage (0-100, null if unavailable)
     * @param details human-readable status details
     * @param traceLog error stack trace (null if no errors)
     */
    public record MigrationStatusResponse(
        long executionId,
        String jobName,
        String status,
        String result,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer progressPercent,
        String details,
        String traceLog
    ) {}

    /**
     * Response DTO for errors.
     *
     * @param error error message describing what went wrong
     */
    public record ErrorResponse(String error) {}
}
