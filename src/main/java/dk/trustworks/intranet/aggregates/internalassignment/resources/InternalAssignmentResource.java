package dk.trustworks.intranet.aggregates.internalassignment.resources;

import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentDTO;
import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentRequest;
import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment;
import dk.trustworks.intranet.aggregates.internalassignment.services.InternalAssignmentService;
import dk.trustworks.intranet.security.ScopeGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Internal assignments (JK Team 2.0 WP3, spec §4.3.4).
 *
 * <p>Two path families on one root — {@code /users/{useruuid}/internal-assignments} for the
 * assignee-scoped list and create, {@code /internal-assignments/{uuid}} for the row — plus the
 * approval queue. Row-level authorization is resolved here at the Quarkus layer through
 * {@link ScopeGuard} (self intrinsic; {@code TEAM} reach for a lead); approval and rejection
 * additionally require {@code teams:write} reach over the assignee and are refused for the
 * assignee themselves inside the service, where the rule cannot be bypassed.
 */
@Tag(name = "Internal Assignments")
@JBossLog
@Path("/")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"users:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class InternalAssignmentResource {

    static final String READ_SCOPE = "users:read";
    static final String WRITE_SCOPE = "users:write";
    static final int MAX_RANGE_DAYS = 800;

    @Inject
    InternalAssignmentService service;

    @Inject
    ScopeGuard scope;

    @GET
    @Path("/users/{useruuid}/internal-assignments")
    public List<InternalAssignmentDTO> list(@PathParam("useruuid") String useruuid,
                                            @QueryParam("fromdate") String fromdate,
                                            @QueryParam("todate") String todate) {
        scope.requireSubjectWhenActor(READ_SCOPE, useruuid, "Internal assignments outside your reach");
        LocalDate from = parseDate(fromdate, "fromdate");
        LocalDate to = parseDate(todate, "todate");
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new WebApplicationException("Invalid range", Response.Status.BAD_REQUEST);
        }
        return service.listForUser(useruuid, from, to);
    }

    @POST
    @Path("/users/{useruuid}/internal-assignments")
    @RolesAllowed({"users:write"})
    public Response create(@PathParam("useruuid") String useruuid, InternalAssignmentRequest request) {
        scope.requireSubjectWhenActor(WRITE_SCOPE, useruuid, "Internal assignments outside your reach");
        InternalAssignmentDTO created = service.create(useruuid, request, scope.actorOrNull());
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/internal-assignments/{uuid}")
    @RolesAllowed({"users:write"})
    public InternalAssignmentDTO update(@PathParam("uuid") String uuid, InternalAssignmentRequest request) {
        InternalAssignment row = service.require(uuid);
        scope.requireSubjectWhenActor(WRITE_SCOPE, row.getUseruuid(), "Internal assignments outside your reach");
        return service.update(row, request, scope.actorOrNull());
    }

    /** {@code teams:write} + TEAM reach over the assignee; never the assignee themselves. */
    @POST
    @Path("/internal-assignments/{uuid}/approve")
    @RolesAllowed({"teams:write"})
    public InternalAssignmentDTO approve(@PathParam("uuid") String uuid) {
        return service.approve(service.require(uuid), scope.actorOrNull());
    }

    /** Same gate as approve. Optional {@code note} explains the rejection to the junior. */
    @POST
    @Path("/internal-assignments/{uuid}/reject")
    @RolesAllowed({"teams:write"})
    public InternalAssignmentDTO reject(@PathParam("uuid") String uuid, @QueryParam("note") String note) {
        return service.reject(service.require(uuid), scope.actorOrNull(), note);
    }

    /** DRAFT only. */
    @DELETE
    @Path("/internal-assignments/{uuid}")
    @RolesAllowed({"users:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        InternalAssignment row = service.require(uuid);
        scope.requireSubjectWhenActor(WRITE_SCOPE, row.getUseruuid(), "Internal assignments outside your reach");
        service.delete(row, scope.actorOrNull());
        return Response.noContent().build();
    }

    /** The approval queue for the acting lead — DRAFT rows within their {@code teams:write} reach. */
    @GET
    @Path("/internal-assignments/pending")
    @RolesAllowed({"teams:read"})
    public List<InternalAssignmentDTO> pending() {
        String actor = scope.actorOrNull();
        if (actor == null) {
            throw new WebApplicationException("X-Requested-By is required — the queue is resolved per lead",
                    Response.Status.BAD_REQUEST);
        }
        return service.pendingFor(actor);
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new WebApplicationException(name + " is required (yyyy-MM-dd)", Response.Status.BAD_REQUEST);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new WebApplicationException(name + " must be an ISO date (yyyy-MM-dd)", Response.Status.BAD_REQUEST);
        }
    }
}
