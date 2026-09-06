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

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Internal assignments (JK Team 2.0 WP3, spec §4.3.4) — the row family.
 *
 * <p>{@code /internal-assignments/{uuid}} for update and delete, the approval queue under
 * {@code /pending}, and the approve / reject decisions. The assignee-scoped list and create
 * live on {@code /users/{useruuid}/internal-assignments} in
 * {@link UserInternalAssignmentResource}, and the two families must stay two classes:
 * RESTEasy Reactive selects the resource <em>class</em> by its class-level {@code @Path}
 * before it looks at any method, so a class rooted at {@code /} is never consulted for a
 * {@code /users/...} request while other classes own {@code /users} — the router answers
 * 404 "Unable to find matching target resource method" instead.
 *
 * <p>Row-level authorization is resolved here at the Quarkus layer through
 * {@link ScopeGuard} (self intrinsic; {@code TEAM} reach for a lead); approval and rejection
 * additionally require {@code teams:write} reach over the assignee and are refused for the
 * assignee themselves inside the service, where the rule cannot be bypassed.
 */
@Tag(name = "Internal Assignments")
@JBossLog
@Path("/internal-assignments")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"users:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class InternalAssignmentResource {

    static final String WRITE_SCOPE = "users:write";

    @Inject
    InternalAssignmentService service;

    @Inject
    ScopeGuard scope;

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"users:write"})
    public InternalAssignmentDTO update(@PathParam("uuid") String uuid, InternalAssignmentRequest request) {
        InternalAssignment row = service.require(uuid);
        scope.requireSubjectWhenActor(WRITE_SCOPE, row.getUseruuid(), "Internal assignments outside your reach");
        return service.update(row, request, scope.actorOrNull());
    }

    /** {@code teams:write} + TEAM reach over the assignee; never the assignee themselves. */
    @POST
    @Path("/{uuid}/approve")
    @RolesAllowed({"teams:write"})
    public InternalAssignmentDTO approve(@PathParam("uuid") String uuid) {
        return service.approve(service.require(uuid), scope.actorOrNull());
    }

    /** Same gate as approve. Optional {@code note} explains the rejection to the junior. */
    @POST
    @Path("/{uuid}/reject")
    @RolesAllowed({"teams:write"})
    public InternalAssignmentDTO reject(@PathParam("uuid") String uuid, @QueryParam("note") String note) {
        return service.reject(service.require(uuid), scope.actorOrNull(), note);
    }

    /** DRAFT only. */
    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"users:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        InternalAssignment row = service.require(uuid);
        scope.requireSubjectWhenActor(WRITE_SCOPE, row.getUseruuid(), "Internal assignments outside your reach");
        service.delete(row, scope.actorOrNull());
        return Response.noContent().build();
    }

    /** The approval queue for the acting lead — DRAFT rows within their {@code teams:write} reach. */
    @GET
    @Path("/pending")
    @RolesAllowed({"teams:read"})
    public List<InternalAssignmentDTO> pending() {
        String actor = scope.actorOrNull();
        if (actor == null) {
            throw new WebApplicationException("X-Requested-By is required — the queue is resolved per lead",
                    Response.Status.BAD_REQUEST);
        }
        return service.pendingFor(actor);
    }
}
