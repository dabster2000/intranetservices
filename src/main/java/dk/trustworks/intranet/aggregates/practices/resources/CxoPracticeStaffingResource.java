package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeStaffingResponseDTO;
import dk.trustworks.intranet.aggregates.practices.services.CxoPracticeStaffingService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/** Protected group staffing endpoint; legal-entity filters are intentionally not accepted. */
@Tag(name = "practices")
@Path("/finance/cxo/practice-staffing")
@RequestScoped
@Produces(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CxoPracticeStaffingResource {

    @Inject
    CxoPracticeStaffingService service;

    @GET
    public PracticeStaffingResponseDTO getStaffing(@QueryParam("practice") String practice) {
        try {
            return service.getStaffing(practice);
        } catch (IllegalArgumentException invalidPractice) {
            throw new BadRequestException(invalidPractice.getMessage());
        }
    }
}
