package dk.trustworks.intranet.snapshot.resources;

import dk.trustworks.intranet.snapshot.model.ImmutableSnapshot;
import dk.trustworks.intranet.snapshot.service.SnapshotService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RESTful resource for managing immutable snapshots across entity types.
 * Provides generic CRUD operations following REST API best practices.
 */
@Tag(name = "snapshots", description = "Manage immutable snapshots for audit compliance across entity types")
@Path("/snapshots")
@RequestScoped
@SecurityRequirement(name = "jwt")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM", "ADMIN"})
public class SnapshotResource {

    @Inject
    SnapshotService service;

    /**
     * Request payload for creating snapshots.
     */
    public record CreateSnapshotRequest(
        @Schema(description = "Entity type discriminator", example = "bonus_pool", required = true)
        String entityType,

        @Schema(description = "Business entity identifier", example = "2024", required = true)
        String entityId,

        @Schema(description = "Entity data as JSON string or object", required = true)
        Object data,

        @Schema(description = "Username or email creating the snapshot", example = "admin@trustworks.dk", required = true)
        String lockedBy
    ) {}

    /**
     * Summary response for list operations.
     */
    public record SnapshotSummary(
        String entityType,
        String entityId,
        Integer snapshotVersion,
        String lockedBy,
        LocalDateTime lockedAt,
        String checksum,
        Integer dataSize,
        String metadata
    ) {
        public static SnapshotSummary from(ImmutableSnapshot snapshot) {
            return new SnapshotSummary(
                snapshot.getEntityType(),
                snapshot.getEntityId(),
                snapshot.getSnapshotVersion(),
                snapshot.getLockedBy(),
                snapshot.getLockedAt(),
                snapshot.getChecksum(),
                snapshot.getSnapshotData() != null ? snapshot.getSnapshotData().length() : 0,
                snapshot.getMetadata()
            );
        }
    }

    @GET
    @Operation(
        summary = "List all snapshots with optional filtering",
        description = "Returns snapshots with optional filtering by entity type, user, and date range"
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "OK - list of snapshot summaries",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = SnapshotSummary[].class)
            )
        ),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public List<SnapshotSummary> listAll(
            @Parameter(description = "Entity type filter") @QueryParam("entityType") String entityType,
            @Parameter(description = "User filter") @QueryParam("lockedBy") String lockedBy,
            @Parameter(description = "Date filter (after)") @QueryParam("after") String after,
            @Parameter(description = "Page number (0-based)", example = "0") @QueryParam("page") @DefaultValue("0") int page,
            @Parameter(description = "Page size", example = "50") @QueryParam("size") @DefaultValue("50") int size) {

        List<ImmutableSnapshot> snapshots;

        // Apply filters
        if (entityType != null && !entityType.isBlank()) {
            snapshots = service.findByEntityType(entityType, page, size);
        } else if (lockedBy != null && !lockedBy.isBlank()) {
            snapshots = service.findByLockedBy(lockedBy);
        } else if (after != null && !after.isBlank()) {
            LocalDateTime dateTime = LocalDateTime.parse(after);
            snapshots = service.findLockedAfter(dateTime);
        } else {
            snapshots = service.findAll(page, size);
        }

        return snapshots.stream()
            .map(SnapshotSummary::from)
            .toList();
    }

    @GET
    @Path("/{entityType}")
    @Operation(
        summary = "List snapshots by entity type",
        description = "Returns all snapshots for a specific entity type with pagination"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "OK - list of snapshots"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public List<SnapshotSummary> listByEntityType(
            @Parameter(description = "Entity type", example = "bonus_pool", required = true)
            @PathParam("entityType") String entityType,
            @Parameter(description = "Page number (0-based)", example = "0") @QueryParam("page") @DefaultValue("0") int page,
            @Parameter(description = "Page size", example = "50") @QueryParam("size") @DefaultValue("50") int size) {

        return service.findByEntityType(entityType, page, size).stream()
            .map(SnapshotSummary::from)
            .toList();
    }

    @GET
    @Path("/{entityType}/{entityId}")
    @Operation(
        summary = "Get latest snapshot for an entity",
        description = "Returns the most recent snapshot version for the specified entity"
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "OK - snapshot found",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ImmutableSnapshot.class)
            )
        ),
        @APIResponse(responseCode = "404", description = "Not found - no snapshots exist"),
        @APIResponse(responseCode = "500", description = "Data integrity check failed"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response getLatestSnapshot(
            @Parameter(description = "Entity type", example = "bonus_pool", required = true)
            @PathParam("entityType") String entityType,
            @Parameter(description = "Entity ID", example = "2024", required = true)
            @PathParam("entityId") String entityId) {

        Optional<ImmutableSnapshot> snapshot = service.getLatestSnapshot(entityType, entityId);

        if (snapshot.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(String.format("No snapshots found for %s:%s", entityType, entityId))
                .build();
        }

        return Response.ok(snapshot.get()).build();
    }

    @GET
    @Path("/{entityType}/{entityId}/{version}")
    @Operation(
        summary = "Get specific snapshot version",
        description = "Returns a specific version of a snapshot with checksum validation"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "OK - snapshot found"),
        @APIResponse(responseCode = "404", description = "Not found - version doesn't exist"),
        @APIResponse(responseCode = "500", description = "Data integrity check failed"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response getSnapshotVersion(
            @Parameter(description = "Entity type", example = "bonus_pool", required = true)
            @PathParam("entityType") String entityType,
            @Parameter(description = "Entity ID", example = "2024", required = true)
            @PathParam("entityId") String entityId,
            @Parameter(description = "Version number", example = "1", required = true)
            @PathParam("version") Integer version) {

        Optional<ImmutableSnapshot> snapshot = service.getSnapshot(entityType, entityId, version);

        if (snapshot.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(String.format("Snapshot not found: %s:%s:v%d", entityType, entityId, version))
                .build();
        }

        return Response.ok(snapshot.get()).build();
    }

    @GET
    @Path("/{entityType}/{entityId}/versions")
    @Operation(
        summary = "List all versions of an entity",
        description = "Returns all snapshot versions for an entity, ordered by version descending"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "OK - list of versions"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public List<SnapshotSummary> getAllVersions(
            @Parameter(description = "Entity type", example = "bonus_pool", required = true)
            @PathParam("entityType") String entityType,
            @Parameter(description = "Entity ID", example = "2024", required = true)
            @PathParam("entityId") String entityId) {

        return service.getAllVersions(entityType, entityId).stream()
            .map(SnapshotSummary::from)
            .toList();
    }

    @GET
    @Path("/{entityType}/{entityId}/exists")
    @Operation(
        summary = "Check if entity has snapshots",
        description = "Returns boolean indicating whether any snapshots exist for the entity"
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = Boolean.class)
            )
        ),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public boolean exists(
            @Parameter(description = "Entity type", example = "bonus_pool", required = true)
            @PathParam("entityType") String entityType,
            @Parameter(description = "Entity ID", example = "2024", required = true)
            @PathParam("entityId") String entityId) {

        return service.isSnapshotted(entityType, entityId);
    }

    @GET
    @Path("/stats")
    @Operation(
        summary = "Get snapshot statistics",
        description = "Returns counts of snapshots by entity type"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "OK - statistics map"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Map<String, Long> getStatistics() {
        Map<String, Long> stats = new HashMap<>();

        for (String entityType : service.getRegisteredEntityTypes()) {
            long count = service.countByEntityType(entityType);
            stats.put(entityType, count);
        }

        // Add total count
        long total = stats.values().stream().mapToLong(Long::longValue).sum();
        stats.put("total", total);

        return stats;
    }

    @POST
    @Operation(
        summary = "Create new snapshot",
        description = "Creates an immutable snapshot with automatic versioning and checksum generation"
    )
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Created - snapshot successfully created",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ImmutableSnapshot.class)
            )
        ),
        @APIResponse(responseCode = "400", description = "Bad request - invalid input or validation failed"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response createSnapshot(
            @RequestBody(required = true, description = "Snapshot creation request",
                content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = CreateSnapshotRequest.class),
                    examples = @ExampleObject(value = "{\"entityType\": \"bonus_pool\", \"entityId\": \"2024\", \"data\": \"{...}\", \"lockedBy\": \"admin@trustworks.dk\"}")))
            CreateSnapshotRequest request) {

        // Validate request
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        if (request.entityType == null || request.entityType.isBlank()) {
            throw new BadRequestException("entityType is required");
        }
        if (request.entityId == null || request.entityId.isBlank()) {
            throw new BadRequestException("entityId is required");
        }
        if (request.data == null) {
            throw new BadRequestException("data is required");
        }
        if (request.lockedBy == null || request.lockedBy.isBlank()) {
            throw new BadRequestException("lockedBy is required");
        }

        // Create snapshot
        ImmutableSnapshot snapshot = service.createSnapshot(
            request.entityType,
            request.entityId,
            request.data,
            request.lockedBy
        );

        return Response.status(Response.Status.CREATED).entity(snapshot).build();
    }

    @DELETE
    @Path("/{entityType}/{entityId}/{version}")
    @Operation(
        summary = "Delete snapshot version",
        description = "DANGEROUS: Deletes a snapshot version. This breaks the audit trail and should only be used in exceptional circumstances."
    )
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Deleted successfully"),
        @APIResponse(responseCode = "404", description = "Not found - version doesn't exist"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response deleteSnapshot(
            @Parameter(description = "Entity type", example = "bonus_pool", required = true)
            @PathParam("entityType") String entityType,
            @Parameter(description = "Entity ID", example = "2024", required = true)
            @PathParam("entityId") String entityId,
            @Parameter(description = "Version number", example = "1", required = true)
            @PathParam("version") Integer version) {

        boolean deleted = service.deleteSnapshot(entityType, entityId, version);

        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(String.format("Snapshot not found: %s:%s:v%d", entityType, entityId, version))
                .build();
        }

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{entityType}/{entityId}")
    @Operation(
        summary = "Delete all versions of an entity",
        description = "DANGEROUS: Deletes all snapshot versions for an entity. This breaks the audit trail."
    )
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Deleted successfully"),
        @APIResponse(responseCode = "404", description = "Not found - no snapshots exist"),
        @APIResponse(responseCode = "401", description = "Unauthorized"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response deleteAllVersions(
            @Parameter(description = "Entity type", example = "bonus_pool", required = true)
            @PathParam("entityType") String entityType,
            @Parameter(description = "Entity ID", example = "2024", required = true)
            @PathParam("entityId") String entityId) {

        long deleted = service.deleteAllVersions(entityType, entityId);

        if (deleted == 0) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(String.format("No snapshots found for %s:%s", entityType, entityId))
                .build();
        }

        return Response.noContent().build();
    }
}
