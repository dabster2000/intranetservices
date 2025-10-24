package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.batch.BulkSalaryRecalcJobLauncher;
import jakarta.annotation.security.RolesAllowed;
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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * REST resource for triggering salary recalculation batch jobs.
 */
@Tag(name = "salary-recalc", description = "Trigger salary data recalculation jobs")
@Path("/salary/recalc")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@JBossLog
public class SalaryRecalcResource {

    @Inject
    BulkSalaryRecalcJobLauncher bulkSalaryRecalcJobLauncher;

    /**
     * Trigger salary recalculation for a single user across a date range.
     *
     * <p>This creates a batch job for a single user across the specified date range.
     * The job runs asynchronously in the background. Use the returned execution ID
     * to track progress via the batch tracking API.
     *
     * @param useruuid the user UUID
     * @param request the recalculation request with date range
     * @return response with execution ID and job details
     */
    @POST
    @Path("/user/{useruuid}")
    @Operation(
        summary = "Trigger single-user salary recalculation",
        description = "Starts a batch job to recalculate salary data for one user across a date range. " +
                      "The job runs asynchronously."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Batch job started successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = BulkRecalcResponse.class)
            )
        ),
        @APIResponse(responseCode = "400", description = "Invalid request parameters"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden - requires SYSTEM role")
    })
    public Response triggerSingleUserRecalc(
            @PathParam("useruuid") String useruuid,
            BulkRecalcRequest request) {

        // Validate request
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        if (request.startDate() == null || request.endDate() == null) {
            throw new BadRequestException("startDate and endDate are required");
        }
        if (useruuid == null || useruuid.isBlank()) {
            throw new BadRequestException("useruuid cannot be null or empty");
        }

        LocalDate start = request.startDate();
        LocalDate end = request.endDate();

        if (end.isBefore(start)) {
            throw new BadRequestException("endDate must be on or after startDate");
        }

        // Default thread count if not specified
        int threads = request.threads() != null && request.threads() > 0 ? request.threads() : 0;

        // Launch the batch job (reuse bulk launcher with single-user list)
        long executionId = bulkSalaryRecalcJobLauncher.launch(
            List.of(useruuid),
            start,
            end,
            threads
        );

        // Calculate job details
        long dayCount = ChronoUnit.DAYS.between(start, end) + 1;

        String message = String.format(
            "Batch job started successfully. Processing 1 user × %d days = %d partitions.",
            dayCount, dayCount
        );

        log.infof("Single-user salary recalc triggered: executionId=%d, user=%s, days=%d",
                  executionId, useruuid, dayCount);

        BulkRecalcResponse response = new BulkRecalcResponse(
            executionId,
            message,
            1,  // userCount = 1
            dayCount,
            dayCount  // partitionCount = 1 × dayCount
        );

        return Response.ok(response).build();
    }

    /**
     * Trigger bulk salary recalculation for multiple users across a date range.
     *
     * <p>This creates a batch job with one partition per user × date combination.
     * The job runs asynchronously in the background. Use the returned execution ID
     * to track progress via the batch tracking API.
     *
     * @param request the bulk recalculation request
     * @return response with execution ID and job details
     */
    @POST
    @Path("/bulk")
    @Operation(
        summary = "Trigger bulk salary recalculation",
        description = "Starts a batch job to recalculate salary data for multiple users across a date range. " +
                      "The job runs asynchronously with one partition per user × date combination."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Batch job started successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = BulkRecalcResponse.class)
            )
        ),
        @APIResponse(responseCode = "400", description = "Invalid request parameters"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden - requires SYSTEM role")
    })

    public Response triggerBulkRecalc(BulkRecalcRequest request) {
        // Validate request
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        if (request.userUuids() == null || request.userUuids().isEmpty()) {
            throw new BadRequestException("userUuids list cannot be null or empty");
        }
        if (request.startDate() == null || request.endDate() == null) {
            throw new BadRequestException("startDate and endDate are required");
        }

        LocalDate start = request.startDate();
        LocalDate end = request.endDate();

        if (end.isBefore(start)) {
            throw new BadRequestException("endDate must be on or after startDate");
        }

        // Default thread count if not specified
        int threads = request.threads() != null && request.threads() > 0 ? request.threads() : 0;

        // Launch the batch job
        long executionId = bulkSalaryRecalcJobLauncher.launch(
            request.userUuids(),
            start,
            end,
            threads
        );

        // Calculate job details
        int userCount = request.userUuids().size();
        long dayCount = ChronoUnit.DAYS.between(start, end) + 1;
        long partitionCount = userCount * dayCount;

        String message = String.format(
            "Batch job started successfully. Processing %d users × %d days = %d partitions.",
            userCount, dayCount, partitionCount
        );

        log.infof("Bulk salary recalc triggered: executionId=%d, users=%d, days=%d, partitions=%d",
                  executionId, userCount, dayCount, partitionCount);

        BulkRecalcResponse response = new BulkRecalcResponse(
            executionId,
            message,
            userCount,
            dayCount,
            partitionCount
        );

        return Response.ok(response).build();
    }

    /**
     * Request payload for bulk salary recalculation.
     */
    @Schema(description = "Request to trigger bulk salary recalculation")
    public record BulkRecalcRequest(
        @Schema(description = "List of user UUIDs to process", required = true)
        List<String> userUuids,

        @Schema(description = "Start date (inclusive) in ISO format", required = true, example = "2024-07-01")
        LocalDate startDate,

        @Schema(description = "End date (inclusive) in ISO format", required = true, example = "2025-06-30")
        LocalDate endDate,

        @Schema(description = "Requested thread count (0 for auto)", example = "8")
        Integer threads
    ) {}

    /**
     * Response payload for bulk salary recalculation.
     */
    @Schema(description = "Response after triggering bulk salary recalculation")
    public record BulkRecalcResponse(
        @Schema(description = "Batch job execution ID for tracking")
        long executionId,

        @Schema(description = "Success message with job details")
        String message,

        @Schema(description = "Number of users being processed")
        int userCount,

        @Schema(description = "Number of days in the date range")
        long dayCount,

        @Schema(description = "Total number of partitions (users × days)")
        long partitionCount
    ) {}
}
