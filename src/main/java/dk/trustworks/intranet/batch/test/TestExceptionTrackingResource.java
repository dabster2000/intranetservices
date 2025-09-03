package dk.trustworks.intranet.batch.test;

import dk.trustworks.intranet.batch.monitoring.BatchJobExecutionTracking;
import dk.trustworks.intranet.batch.monitoring.BatchJobTrackingQuery;
import jakarta.annotation.security.RolesAllowed;
import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Properties;

/**
 * REST endpoint for testing exception tracking functionality.
 * 
 * This endpoint allows you to trigger test jobs that fail in various ways
 * to validate that exceptions are properly captured and persisted.
 * 
 * Usage:
 * POST /batch/test/exception?type=nullpointer
 * GET /batch/test/exception/status/{executionId}
 */
@Tag(name = "batch-test")
@Path("/batch/test/exception")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@JBossLog
public class TestExceptionTrackingResource {
    
    @Inject
    JobOperator jobOperator;
    
    @Inject
    BatchJobTrackingQuery trackingQuery;
    
    /**
     * Triggers a test job that will fail with the specified exception type.
     * 
     * @param exceptionType Type of exception to throw: 
     *                     nullpointer, illegalargument, sql, filenotfound, 
     *                     nested, error, random, or success (no exception)
     * @return Execution ID of the started job
     */
    @POST
    public Response triggerTestJob(@QueryParam("type") @DefaultValue("random") String exceptionType) {
        log.infof("Triggering test exception job with type: %s", exceptionType);
        
        Properties props = new Properties();
        System.setProperty("test.exception.type", exceptionType);
        
        try {
            long executionId = jobOperator.start("test-exception-tracking", props);
            
            return Response.ok(new TestJobResponse(
                executionId,
                "test-exception-tracking",
                exceptionType,
                "Job started. Check status endpoint for results."
            )).build();
            
        } catch (Exception e) {
            log.errorf(e, "Failed to start test job");
            return Response.serverError()
                .entity(new ErrorResponse("Failed to start test job: " + e.getMessage()))
                .build();
        }
    }
    
    /**
     * Checks the status and trace log of a test job execution.
     * 
     * @param executionId The execution ID returned from the trigger endpoint
     * @return Job execution details including trace log if available
     */
    @GET
    @Path("/status/{executionId}")
    public Response checkTestJobStatus(@PathParam("executionId") long executionId) {
        return trackingQuery.findByExecutionId(executionId)
            .map(tracking -> {
                TestStatusResponse response = new TestStatusResponse(
                    tracking.getExecutionId(),
                    tracking.getJobName(),
                    tracking.getStatus(),
                    tracking.getResult(),
                    tracking.getExitStatus(),
                    tracking.getProgressPercent(),
                    tracking.getStartTime() != null ? tracking.getStartTime().toString() : null,
                    tracking.getEndTime() != null ? tracking.getEndTime().toString() : null,
                    tracking.getDetails(),
                    tracking.getTraceLog() != null ? tracking.getTraceLog().substring(0, 
                        Math.min(tracking.getTraceLog().length(), 5000)) : null,
                    tracking.getTraceLog() != null
                );
                
                return Response.ok(response).build();
            })
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Execution not found: " + executionId))
                .build());
    }
    
    /**
     * Validates that exception tracking is working by running a quick test.
     * 
     * @return Validation results
     */
    @GET
    @Path("/validate")
    public Response validateExceptionTracking() {
        log.info("Starting exception tracking validation");
        
        try {
            // Start a job that will fail
            Properties props = new Properties();
            System.setProperty("test.exception.type", "nullpointer");
            long executionId = jobOperator.start("test-exception-tracking", props);
            
            // Wait for job to complete (max 10 seconds)
            for (int i = 0; i < 20; i++) {
                Thread.sleep(500);
                
                BatchJobExecutionTracking tracking = trackingQuery.findByExecutionId(executionId).orElse(null);
                if (tracking != null && tracking.getEndTime() != null) {
                    // Job completed, check if trace was captured
                    boolean hasTrace = tracking.getTraceLog() != null && !tracking.getTraceLog().isEmpty();
                    boolean isFailed = "FAILED".equals(tracking.getStatus()) || "FAILED".equals(tracking.getResult());
                    
                    ValidationResponse validation = new ValidationResponse(
                        hasTrace && isFailed,
                        hasTrace ? "Exception trace captured successfully" : "No exception trace found",
                        executionId,
                        tracking.getStatus(),
                        tracking.getResult(),
                        hasTrace
                    );
                    
                    return Response.ok(validation).build();
                }
            }
            
            return Response.ok(new ValidationResponse(
                false,
                "Job did not complete within timeout",
                executionId,
                null,
                null,
                false
            )).build();
            
        } catch (Exception e) {
            log.errorf(e, "Validation failed");
            return Response.serverError()
                .entity(new ErrorResponse("Validation failed: " + e.getMessage()))
                .build();
        }
    }
    
    // Response DTOs
    
    public record TestJobResponse(
        long executionId,
        String jobName,
        String exceptionType,
        String message
    ) {}
    
    public record TestStatusResponse(
        long executionId,
        String jobName,
        String status,
        String result,
        String exitStatus,
        Integer progressPercent,
        String startTime,
        String endTime,
        String details,
        String traceLogSnippet,
        boolean hasTraceLog
    ) {}
    
    public record ValidationResponse(
        boolean success,
        String message,
        long executionId,
        String status,
        String result,
        boolean traceLogCaptured
    ) {}
    
    public record ErrorResponse(String error) {}
}