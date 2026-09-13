package dk.trustworks.intranet.aggregates.crm.gtm.resources;

import dk.trustworks.intranet.aggregates.crm.gtm.dto.GtmTeamDTO;
import dk.trustworks.intranet.aggregates.crm.gtm.dto.GtmTeamSectorsRequest;
import dk.trustworks.intranet.aggregates.crm.gtm.services.GtmTeamService;
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
 * GTM teams (sectors spec §5): the active FOCUS bubbles with their sectors and accounts.
 *
 * <p>Membership is not here — join, apply and leave stay on {@code /bubbles}, where they
 * always were. What this resource owns is the two things a bubble does not know about
 * itself: which sectors it covers and which accounts point at it.
 */
@Tag(name = "crm")
@JBossLog
@Path("/gtm-teams")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"accounts:read"})
public class GtmTeamResource {

    @Inject
    GtmTeamService gtmTeamService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    public List<GtmTeamDTO> list() {
        return gtmTeamService.list();
    }

    /** Replaces the set of sectors a team covers. The team's lead, or management, by person. */
    @PUT
    @Path("/{bubbleUuid}/sectors")
    @RolesAllowed({"accounts:write"})
    public GtmTeamDTO replaceSectors(@PathParam("bubbleUuid") String bubbleUuid, GtmTeamSectorsRequest request) {
        return gtmTeamService.replaceSectors(bubbleUuid, request, requireActor());
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
