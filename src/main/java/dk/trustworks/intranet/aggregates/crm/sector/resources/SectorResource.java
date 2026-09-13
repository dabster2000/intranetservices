package dk.trustworks.intranet.aggregates.crm.sector.resources;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.services.PersonRoleService;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorDTO;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorLeadRequest;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorSummaryDTO;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorLeadService;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorService;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Sectors (sectors spec §5): the six cards, one sector's page, and who leads it.
 *
 * <p><b>Its own root.</b> {@code /sectors} is a prefix no other class owns; nesting under
 * {@code /clients} or {@code /accounts} would be shadowed by the class that owns the prefix
 * and answer 404 "Unable to find matching target resource method".
 *
 * <p>Reads are {@code accounts:read} — every employee; sectors are as firm-readable as the
 * accounts in them. Naming a lead is {@code accounts:write} at the door and a partner-group
 * decision inside: {@code SectorLeadService} resolves ADMIN/PARTNER from the PERSON.
 *
 * <p>The break-even on the rate tile is salary-derived and is filtered by the person's
 * cost role, server-side, so it never reaches a browser that may not show it.
 */
@Tag(name = "crm")
@JBossLog
@Path("/sectors")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"accounts:read"})
public class SectorResource {

    @Inject
    SectorService sectorService;

    @Inject
    SectorLeadService sectorLeadService;

    @Inject
    PersonRoleService personRoles;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /** Always six cards, in the Industries-tab order. */
    @GET
    public List<SectorSummaryDTO> summaries() {
        return sectorService.summaries(personRoles.maySeeCost(actor()));
    }

    @GET
    @Path("/{segment}")
    public SectorDTO read(@PathParam("segment") String segment) {
        String actor = actor();
        return sectorService.read(SectorService.parseSegment(segment), personRoles.maySeeCost(actor), personRoles.isManagement(actor));
    }

    /** Names the sector lead from today, or clears it. Returns the new lead, or null when cleared. */
    @PUT
    @Path("/{segment}/lead")
    @RolesAllowed({"accounts:write"})
    public Response setLead(@PathParam("segment") String segment, SectorLeadRequest request) {
        PersonDTO lead = sectorLeadService.setLead(SectorService.parseSegment(segment), request, requireActor());
        return Response.ok(lead).build();
    }

    /** The person behind the request, or null — reads are open, so nobody is refused here. */
    private String actor() {
        String actor = requestHeaderHolder.getUserUuid();
        return actor == null || actor.isBlank() ? null : actor.trim();
    }

    private String requireActor() {
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException("X-Requested-By header is required", Response.Status.BAD_REQUEST);
        }
        try {
            java.util.UUID.fromString(actor.trim());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("X-Requested-By is not a valid UUID", Response.Status.BAD_REQUEST);
        }
        return actor.trim();
    }
}
