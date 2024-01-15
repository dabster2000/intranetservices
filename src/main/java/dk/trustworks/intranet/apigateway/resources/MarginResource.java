package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.marginservice.dto.ClientMarginResult;
import dk.trustworks.intranet.marginservice.dto.MarginResult;
import dk.trustworks.intranet.marginservice.services.MarginService;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "margin")
@Path("/margin")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class MarginResource {

    @Inject
    MarginService marginService;

    @GET
    @Path("/{useruuid}/{rate}")
    public MarginResult calculateMargin(@PathParam("useruuid") String useruuid, @PathParam("rate") int rate) {
        return marginService.calculateMargin(useruuid, rate);
    }

    @GET
    @Path("/clients")
    public List<ClientMarginResult> calculateClientMargins(@QueryParam("fiscalyear") int fiscalYear) {
        return marginService.calculateClientMargins(fiscalYear);
    }
}