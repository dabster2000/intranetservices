package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionResponseDTO;
import dk.trustworks.intranet.aggregates.practices.services.CxoPracticeContributionService;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/** Aggregate-only contribution endpoint; it never serializes consultant or invoice detail. */
@Tag(name = "practices")
@Path("/practices/cxo/contribution")
@RequestScoped
@Produces(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CxoPracticeContributionResource {

    @Inject
    CxoPracticeContributionService service;

    @GET
    public PracticeContributionResponseDTO getContribution(@Context UriInfo uriInfo) {
        // Decoded (the default). Quarkus REST rejects getQueryParameters(false) outright with
        // "We do not support non-decoded parameters", making every call a 500.
        List<String> values = uriInfo.getQueryParameters().get("costSource");
        return service.getContribution(parseCostSource(values));
    }

    static CostSource parseCostSource(List<String> values) {
        if (values == null) return CostSource.BOOKED;
        if (values.size() != 1) throw invalidCostSource();
        String value = values.getFirst();
        if ("BOOKED".equals(value)) return CostSource.BOOKED;
        if ("BOOKED_PLUS_DRAFT".equals(value)) return CostSource.BOOKED_PLUS_DRAFT;
        throw invalidCostSource();
    }

    private static BadRequestException invalidCostSource() {
        return new BadRequestException("costSource must be exactly BOOKED or BOOKED_PLUS_DRAFT");
    }
}
