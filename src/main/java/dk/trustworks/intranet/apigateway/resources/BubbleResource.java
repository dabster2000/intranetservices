package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.bubbleservice.model.Bubble;
import dk.trustworks.intranet.dao.bubbleservice.services.BubbleService;
import io.micrometer.core.annotation.Timed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "Bubble")
@Path("/bubbles")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"SYSTEM", "USER"})
@SecurityRequirement(name = "jwt")
@Timed(histogram = true)
public class BubbleResource {

    @Inject
    BubbleService bubbleService;

    @GET
    public List<Bubble> findAll(@QueryParam("useruuid") String ownerUseruuid) {
        return bubbleService.findAll(ownerUseruuid);
    }

    @GET
    @Path("/active")
    public List<Bubble> findBubblesByActiveTrueOrderByCreatedDesc() {
        return bubbleService.findBubblesByActiveTrueOrderByCreatedDesc();
    }

    @POST
    public void save(Bubble bubble) {
        bubbleService.save(bubble);
    }

    @PUT
    public void update(Bubble bubble) {
        bubbleService.update(bubble);
    }

    @DELETE
    @Path("/{bubbleuuid}")
    @RolesAllowed({"CXO"})
    public void delete(@PathParam("bubbleuuid") String bubbleuuid) {
        bubbleService.delete(bubbleuuid);
    }

    @POST
    @Path("/{bubbleuuid}/users/{useruuid}")
    public void addBubbleMember(@PathParam("bubbleuuid") String bubbleuuid, @PathParam("useruuid") String useruuid) {
        bubbleService.addBubbleMember(bubbleuuid, useruuid);
    }

    @DELETE
    @Path("/{bubbleuuid}/users/{useruuid}")
    public void removeBubbleMember(@PathParam("bubbleuuid") String bubbleuuid, @PathParam("useruuid") String useruuid) {
        bubbleService.removeBubbleMember(bubbleuuid, useruuid);
    }

    @DELETE
    @Path("/{bubbleuuid}/users")
    public void removeBubbleMembers(@PathParam("bubbleuuid") String bubbleuuid) {
        bubbleService.removeBubbleMembers(bubbleuuid);
    }
}