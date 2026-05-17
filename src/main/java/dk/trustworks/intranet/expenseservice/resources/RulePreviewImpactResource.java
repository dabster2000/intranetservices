package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.PreviewImpactRequestDTO;
import dk.trustworks.intranet.expenseservice.dto.PreviewImpactResponseDTO;
import dk.trustworks.intranet.expenseservice.services.PreviewImpactService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/admin/rules/preview-impact")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:write"})
public class RulePreviewImpactResource {

    private final PreviewImpactService service;

    public RulePreviewImpactResource(PreviewImpactService service) {
        this.service = service;
    }

    @POST
    public PreviewImpactResponseDTO preview(@Valid PreviewImpactRequestDTO req) {
        return service.preview(req);
    }
}
