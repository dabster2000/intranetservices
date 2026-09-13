package dk.trustworks.intranet.aggregates.crm.bid.resources;

import dk.trustworks.intranet.aggregates.crm.bid.dto.BidDTO;
import dk.trustworks.intranet.aggregates.crm.bid.dto.BidRequest;
import dk.trustworks.intranet.aggregates.crm.bid.services.BidService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Bid records (CRM spec §3.6).
 *
 * <p>{@code bids:read} is granted to every employee: the bid history sits on an account
 * page anyone can open, and a consultant walking into a client should be able to see that
 * we bid there and lost. {@code bids:write} is the sales tier — filing a bid record is a
 * commercial act.
 *
 * <p><b>Its own root.</b> {@code /bids} is a prefix no other class owns.
 */
@Tag(name = "crm")
@JBossLog
@Path("/bids")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"bids:read"})
public class BidResource {

    @Inject
    BidService bidService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    public List<BidDTO> list(@QueryParam("clientUuid") String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            // Deliberately not "every bid in the firm": the only caller is an account page,
            // and an unfiltered list would be a quiet firm-wide commercial export.
            throw new WebApplicationException("clientUuid is required", Response.Status.BAD_REQUEST);
        }
        return bidService.forClient(clientUuid);
    }

    @POST
    @RolesAllowed({"bids:write"})
    public Response create(BidRequest request) {
        BidDTO bid = bidService.create(request, requireActor());
        return Response.created(java.net.URI.create("/bids/" + bid.uuid())).entity(bid).build();
    }

    @PATCH
    @Path("/{uuid}")
    @RolesAllowed({"bids:write"})
    public BidDTO update(@PathParam("uuid") String uuid, BidRequest request) {
        return bidService.update(uuid, request, requireActor());
    }

    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"bids:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        bidService.delete(uuid, requireActor());
        return Response.noContent().build();
    }

    /**
     * The acting employee. {@code X-Requested-By} falls back to the BFF's client id when
     * the header is missing, so anything that is not a uuid is refused.
     */
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
