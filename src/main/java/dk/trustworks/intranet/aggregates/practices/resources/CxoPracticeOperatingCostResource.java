package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.aggregates.practices.services.CxoPracticeOperatingCostService;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/** Protected, group-only practice operating-cost endpoint. */
@Tag(name = "practices")
@Path("/practices/cxo/operating-cost")
@RequestScoped
@Produces(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CxoPracticeOperatingCostResource {

    @Inject
    CxoPracticeOperatingCostService service;

    @GET
    public PracticeOperatingCostResponseDTO getOperatingCost(@QueryParam("costSource") String costSource) {
        return service.getOperatingCost(CostSource.fromQueryParam(costSource));
    }
}
