package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.workservice.model.Week;
import dk.trustworks.intranet.dao.workservice.services.WeekService;
import dk.trustworks.intranet.security.ScopeGuard;
import jakarta.annotation.security.RolesAllowed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "time")
@Path("/weeks")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"timeregistration:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class WeekResource {

    @Inject
    WeekService weekService;

    @Inject
    ScopeGuard scope;

    // Phase 10.1: subject checks are inert while every timeregistration grant
    // is ALL (access-intent Decision 14) — they pin the enforcement point for
    // a future console narrowing.

    @POST
    @RolesAllowed({"timeregistration:write"})
    public void saveWeek(Week week) {
        scope.requireSubjectWhenActor(WorkResource.WRITE_SCOPE, week.getUseruuid(),
                "Week rows outside your reach");
        weekService.save(week);
    }

    @PUT
    @Path("/{weekuuid}")
    @RolesAllowed({"timeregistration:write"})
    public void updateWeekSorting(@PathParam("weekuuid") String weekuuid, UpdateWeekSortingRequest request) {
        if (request == null || request.sorting() == null) {
            throw new BadRequestException("Request body with a sorting value is required");
        }
        weekService.updateSorting(weekuuid, request.sorting());
    }

    /**
     * Deliberately narrow request body: the row's identity tuple (taskuuid, useruuid,
     * weeknumber, year) must not be reachable through this endpoint, and POST /weeks
     * intentionally never touches sorting on an existing row (see WeekService#save) —
     * so display order can only be changed here. Boxed Integer so an absent field is a
     * 400, not a silent sorting=0.
     */
    public record UpdateWeekSortingRequest(Integer sorting) {}

    @DELETE
    @Path("/{weekuuid}")
    @RolesAllowed({"timeregistration:write"})
    public void deleteWeek(@PathParam("weekuuid") String weekuuid) {
        Week week = findWeek(weekuuid);
        if (week != null) {
            scope.requireSubjectWhenActor(WorkResource.WRITE_SCOPE, week.getUseruuid(),
                    "Week rows outside your reach");
        }
        weekService.delete(weekuuid);
    }

    /** Package-private seam so the scope tests can stub the row lookup without Panache. */
    Week findWeek(String weekuuid) {
        return Week.findById(weekuuid);
    }
}
