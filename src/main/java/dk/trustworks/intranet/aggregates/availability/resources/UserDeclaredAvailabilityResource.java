package dk.trustworks.intranet.aggregates.availability.resources;

import dk.trustworks.intranet.aggregates.availability.config.DeclaredAvailabilityPolicy;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityCopyRequest;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityDTO;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityModeDTO;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityRangeDTO;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityUpsertRequest;
import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability.Source;
import dk.trustworks.intranet.aggregates.availability.services.DeclaredAvailabilityService;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Declared availability (JK Team 2.0 WP1, spec §4.1.3).
 *
 * <p>Row-level authorization happens here, at the Quarkus layer, through {@link ScopeGuard}:
 * a person always reaches their own rows ({@code OWN} is intrinsic to
 * {@code decideSubjectAccess}); anyone else needs {@code users:read} / {@code users:write}
 * with reach over the subject — {@code TEAM} for a team lead. The BFF shares one token, so
 * a check that lived only there would be no check.
 *
 * <p>Bodies are DTOs, never the entity; the subject is the path and the source
 * ({@code SELF} / {@code TEAMLEAD} / {@code SYSTEM}) is derived from the actor, so neither
 * can be forged in a body.
 */
@Tag(name = "User Declared Availability")
@JBossLog
@Path("/users")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"users:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UserDeclaredAvailabilityResource {

    static final String READ_SCOPE = "users:read";
    static final String WRITE_SCOPE = "users:write";
    /** Longest range one GET may ask for — a year and a bit. */
    static final int MAX_RANGE_DAYS = 400;

    @Inject
    DeclaredAvailabilityService service;

    @Inject
    DeclaredAvailabilityPolicy policy;

    @Inject
    ScopeGuard scope;

    /** The cut-over state, read-only. Same gate as the other availability reads. */
    @GET
    @Path("/declared-availability/mode")
    @RolesAllowed({"availability:read"})
    public DeclaredAvailabilityModeDTO mode() {
        return new DeclaredAvailabilityModeDTO(policy.mode().name(), policy.floorDate());
    }

    @GET
    @Path("/{useruuid}/declared-availability")
    public DeclaredAvailabilityRangeDTO get(@PathParam("useruuid") String useruuid,
                                            @QueryParam("fromdate") String fromdate,
                                            @QueryParam("todate") String todate) {
        scope.requireSubjectWhenActor(READ_SCOPE, useruuid, "Declared availability outside your reach");
        LocalDate from = parseDate(fromdate, "fromdate");
        LocalDate to = parseDate(todate, "todate");
        if (to.isBefore(from)) {
            throw new WebApplicationException("todate must not be before fromdate", Response.Status.BAD_REQUEST);
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new WebApplicationException("Range must be at most " + MAX_RANGE_DAYS + " days",
                    Response.Status.BAD_REQUEST);
        }
        return service.range(useruuid, from, to);
    }

    /** Idempotent bulk upsert, all-or-nothing. */
    @PUT
    @Path("/{useruuid}/declared-availability")
    @RolesAllowed({"users:write"})
    public List<DeclaredAvailabilityDTO> put(@PathParam("useruuid") String useruuid,
                                             List<DeclaredAvailabilityUpsertRequest> items) {
        scope.requireSubjectWhenActor(WRITE_SCOPE, useruuid, "Declared availability outside your reach");
        String actor = scope.actorOrNull();
        return service.upsert(useruuid, items, sourceFor(actor, useruuid), actor);
    }

    @DELETE
    @Path("/{useruuid}/declared-availability/{day}")
    @RolesAllowed({"users:write"})
    public Response delete(@PathParam("useruuid") String useruuid, @PathParam("day") String day) {
        scope.requireSubjectWhenActor(WRITE_SCOPE, useruuid, "Declared availability outside your reach");
        boolean removed = service.delete(useruuid, parseDate(day, "day"), scope.actorOrNull());
        return removed ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    /** Copy one week onto N target weeks; targets end up mirroring the source. */
    @POST
    @Path("/{useruuid}/declared-availability/copy")
    @RolesAllowed({"users:write"})
    public List<DeclaredAvailabilityDTO> copy(@PathParam("useruuid") String useruuid,
                                              DeclaredAvailabilityCopyRequest request) {
        scope.requireSubjectWhenActor(WRITE_SCOPE, useruuid, "Declared availability outside your reach");
        return service.copyForward(useruuid, request, scope.actorOrNull());
    }

    /** Self → SELF; another human → TEAMLEAD; a headerless machine caller → SYSTEM. */
    static Source sourceFor(String actorUuid, String subjectUuid) {
        if (actorUuid == null) {
            return Source.SYSTEM;
        }
        return actorUuid.equalsIgnoreCase(subjectUuid) ? Source.SELF : Source.TEAMLEAD;
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
