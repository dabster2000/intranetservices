package dk.trustworks.intranet.aggregates.internalassignment.resources;

import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentDTO;
import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentRequest;
import dk.trustworks.intranet.aggregates.internalassignment.services.InternalAssignmentService;
import dk.trustworks.intranet.security.ScopeGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * Internal assignments (JK Team 2.0 WP3, spec §4.3.4) — the assignee-scoped family.
 *
 * <p>{@code GET} and {@code POST /users/{useruuid}/internal-assignments}: the list over a
 * range and create. Rooted at {@code /users}, like
 * {@link dk.trustworks.intranet.aggregates.availability.resources.UserDeclaredAvailabilityResource},
 * so it sits in the class group the router already sends {@code /users/...} requests to; the
 * row endpoints live in {@link InternalAssignmentResource} under {@code /internal-assignments}.
 * The two families cannot share one class — see that class for why.
 *
 * <p>Row-level authorization is resolved here at the Quarkus layer through {@link ScopeGuard}
 * (self intrinsic; {@code TEAM} reach for a lead). The BFF shares one token, so a check that
 * lived only there would be no check.
 */
@Tag(name = "Internal Assignments")
@JBossLog
@Path("/users")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"users:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UserInternalAssignmentResource {

    static final String READ_SCOPE = "users:read";
    static final String WRITE_SCOPE = "users:write";
    /** Longest range one GET may ask for; the profile asks for three months back to a year ahead. */
    static final int MAX_RANGE_DAYS = 800;

    @Inject
    InternalAssignmentService service;

    @Inject
    ScopeGuard scope;

    @GET
    @Path("/{useruuid}/internal-assignments")
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
    @Path("/{useruuid}/internal-assignments")
    @RolesAllowed({"users:write"})
    public Response create(@PathParam("useruuid") String useruuid, InternalAssignmentRequest request) {
        scope.requireSubjectWhenActor(WRITE_SCOPE, useruuid, "Internal assignments outside your reach");
        InternalAssignmentDTO created = service.create(useruuid, request, scope.actorOrNull());
        return Response.status(Response.Status.CREATED).entity(created).build();
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
