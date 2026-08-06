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

    @DELETE
    @Path("/{weekuuid}")
    @RolesAllowed({"timeregistration:write"})
    public void deleteWeek(@PathParam("weekuuid") String weekuuid) {
        Week week = Week.findById(weekuuid);
        if (week != null) {
            scope.requireSubjectWhenActor(WorkResource.WRITE_SCOPE, week.getUseruuid(),
                    "Week rows outside your reach");
        }
        weekService.delete(weekuuid);
    }
}
