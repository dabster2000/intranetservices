package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.marginservice.dto.ClientMarginResult;
import dk.trustworks.intranet.marginservice.dto.MarginResult;
import dk.trustworks.intranet.marginservice.services.MarginService;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "margin")
@Path("/margin")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"CXO", "SALES", "VTV", "ACCOUNTING", "MANAGER", "PARTNER", "ADMIN"})
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