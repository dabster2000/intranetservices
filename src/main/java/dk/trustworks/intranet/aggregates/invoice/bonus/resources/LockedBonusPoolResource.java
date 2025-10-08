package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.LockedBonusPoolData;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.LockedBonusPoolService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Tag(name = "locked-bonus-pool", description = "Manage locked bonus pool data snapshots for fiscal years. Ensures audit compliance and data immutability.")
@Path("/bonuspool/locked")
@RequestScoped
@SecurityRequirement(name = "jwt")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
public class LockedBonusPoolResource {

    @Inject
    LockedBonusPoolService service;

    public record LockRequest(
            @Schema(description = "Fiscal year to lock", example = "2024", required = true)
            Integer fiscalYear,

            @Schema(description = "Complete JSON serialization of FiscalYearPoolContext", required = true)
            String poolContextJson,

            @Schema(description = "Username or email of person locking the data", example = "admin@trustworks.dk", required = true)
            String lockedBy
    ) {}

    public record LockedDataSummary(
            @Schema(description = "Fiscal year", example = "2024")
            Integer fiscalYear,

            @Schema(description = "Username who locked the data", example = "admin@trustworks.dk")
            String lockedBy,

            @Schema(description = "When the data was locked")
            LocalDateTime lockedAt,

            @Schema(description = "SHA-256 checksum")
            String checksum,

            @Schema(description = "Data size in bytes")
            Integer dataSize
    ) {
        public static LockedDataSummary from(LockedBonusPoolData data) {
            return new LockedDataSummary(
                data.fiscalYear,
                data.lockedBy,
                data.lockedAt,
                data.checksum,
                data.poolContextJson != null ? data.poolContextJson.length() : 0
            );
        }
    }

    @GET
    @Operation(
            summary = "List all locked bonus pool data",
            description = "Returns all locked bonus pool snapshots ordered by fiscal year descending"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK - list of locked data summaries",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LockedDataSummary[].class),
                            examples = @ExampleObject(value = "[{\"fiscalYear\": 2024, \"lockedBy\": \"admin@trustworks.dk\", \"lockedAt\": \"2024-07-01T10:00:00\", \"checksum\": \"abc123...\", \"dataSize\": 12345}]")
                    )
            ),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public List<LockedDataSummary> listAll() {
        return service.findAll().stream()
                .map(LockedDataSummary::from)
                .toList();
    }

    @GET
    @Path("/{fiscalYear}")
    @Operation(
            summary = "Get locked bonus pool data by fiscal year",
            description = "Returns the complete locked bonus pool snapshot for a specific fiscal year with checksum validation"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK - locked data found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LockedBonusPoolData.class)
                    )
            ),
            @APIResponse(responseCode = "404", description = "Not found - fiscal year not locked"),
            @APIResponse(responseCode = "500", description = "Data integrity check failed"),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response getByFiscalYear(
            @Parameter(name = "fiscalYear", description = "Fiscal year", example = "2024", required = true)
            @PathParam("fiscalYear") Integer fiscalYear) {

        Optional<LockedBonusPoolData> data = service.getValidatedData(fiscalYear);

        if (data.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(String.format("No locked data found for fiscal year %d", fiscalYear))
                    .build();
        }

        return Response.ok(data.get()).build();
    }

    @GET
    @Path("/{fiscalYear}/exists")
    @Operation(
            summary = "Check if fiscal year is locked",
            description = "Returns boolean indicating whether bonus pool data is locked for the given fiscal year"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = Boolean.class),
                            examples = {@ExampleObject(name = "Locked", value = "true"), @ExampleObject(name = "Not locked", value = "false")}
                    )
            ),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public boolean isLocked(
            @Parameter(name = "fiscalYear", description = "Fiscal year", example = "2024", required = true)
            @PathParam("fiscalYear") Integer fiscalYear) {
        return service.isLocked(fiscalYear);
    }

    @POST
    @Operation(
            summary = "Lock bonus pool data for a fiscal year",
            description = "Creates an immutable snapshot of bonus pool data with SHA-256 checksum. Once locked, data cannot be modified (only deleted by admin in exceptional cases)."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "201",
                    description = "Created - data successfully locked",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LockedBonusPoolData.class)
                    )
            ),
            @APIResponse(responseCode = "400", description = "Bad request - invalid input"),
            @APIResponse(responseCode = "409", description = "Conflict - fiscal year already locked"),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response lockBonusPool(
            @RequestBody(required = true, description = "Lock request payload",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LockRequest.class),
                            examples = @ExampleObject(value = "{\"fiscalYear\": 2024, \"poolContextJson\": \"{...}\", \"lockedBy\": \"admin@trustworks.dk\"}")))
            LockRequest request) {

        if (request == null || request.fiscalYear == null) {
            throw new BadRequestException("fiscalYear is required");
        }

        if (request.poolContextJson == null || request.poolContextJson.isBlank()) {
            throw new BadRequestException("poolContextJson is required");
        }

        if (request.lockedBy == null || request.lockedBy.isBlank()) {
            throw new BadRequestException("lockedBy is required");
        }

        LockedBonusPoolData data = service.lockBonusPool(
                request.fiscalYear,
                request.poolContextJson,
                request.lockedBy
        );

        return Response.status(Response.Status.CREATED).entity(data).build();
    }

    @DELETE
    @Path("/{fiscalYear}")
    @Operation(
            summary = "Unlock (delete) bonus pool data",
            description = "DANGEROUS: Deletes locked bonus pool data for a fiscal year. This breaks the audit trail and should only be used in exceptional circumstances."
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Deleted successfully"),
            @APIResponse(responseCode = "404", description = "Not found - fiscal year was not locked"),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response unlockBonusPool(
            @Parameter(name = "fiscalYear", description = "Fiscal year to unlock", example = "2024", required = true)
            @PathParam("fiscalYear") Integer fiscalYear) {

        boolean deleted = service.unlockBonusPool(fiscalYear);

        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(String.format("No locked data found for fiscal year %d", fiscalYear))
                    .build();
        }

        return Response.noContent().build();
    }

    @GET
    @Path("/by-user/{username}")
    @Operation(
            summary = "Find locks by user",
            description = "Returns all locks created by a specific user"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK - list of locks",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LockedDataSummary[].class)
                    )
            ),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public List<LockedDataSummary> findByUser(
            @Parameter(name = "username", description = "Username or email", example = "admin@trustworks.dk", required = true)
            @PathParam("username") String username) {
        return service.findByLockedBy(username).stream()
                .map(LockedDataSummary::from)
                .toList();
    }

    @GET
    @Path("/after/{timestamp}")
    @Operation(
            summary = "Find locks created after timestamp",
            description = "Returns all locks created after the specified timestamp"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK - list of locks",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LockedDataSummary[].class)
                    )
            ),
            @APIResponse(responseCode = "400", description = "Bad request - invalid timestamp"),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public List<LockedDataSummary> findAfter(
            @Parameter(name = "timestamp", description = "ISO timestamp", example = "2024-01-01T00:00:00", required = true)
            @PathParam("timestamp") String timestamp) {

        LocalDateTime dateTime;
        try {
            dateTime = LocalDateTime.parse(timestamp);
        } catch (Exception e) {
            throw new BadRequestException("Invalid timestamp format. Use ISO format: yyyy-MM-ddTHH:mm:ss");
        }

        return service.findLockedAfter(dateTime).stream()
                .map(LockedDataSummary::from)
                .toList();
    }
}
