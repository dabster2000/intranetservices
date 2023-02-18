package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.workservice.model.Week;
import dk.trustworks.intranet.dao.workservice.services.WeekService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "time")
@Path("/weeks")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@ClientHeaderParam(name="Authorization", value="{generateRequestId}")
@RolesAllowed({"USER", "EXTERNAL", "EDITOR", "CXO", "SALES", "VTV", "ACCOUNTING", "MANAGER", "PARTNER", "ADMIN"})
public class WeekResource {

    @Inject
    WeekService weekService;

    @POST
    public void saveWeek(Week week) {
        weekService.save(week);
    }

    @DELETE
    @Path("/{weekuuid}")
    public void deleteWeek(@PathParam("weekuuid") String weekuuid) {
        weekService.delete(weekuuid);
    }
}
