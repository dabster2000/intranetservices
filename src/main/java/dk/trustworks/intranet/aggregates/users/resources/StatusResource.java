package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateUserStatusEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteUserStatusEvent;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import io.quarkus.cache.CacheInvalidateAll;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class StatusResource {

    @Inject
    StatusService statusService;

    @Inject
    AggregateEventSender aggregateEventSender;

    @GET
    @Path("/{useruuid}/statuses")
    public List<UserStatus> listAll(@PathParam("useruuid") String useruuid) {
        return statusService.listAll(useruuid);
    }

    @GET
    @Path("/{useruuid}/statuses/employed/first")
    public UserStatus getFirstEmploymentStatus(@PathParam("useruuid") String useruuid) {
        return statusService.getFirstEmploymentStatus(useruuid);
    }

    @GET
    @Path("/{useruuid}/statuses/employed/latest")
    public UserStatus getLatestEmploymentStatus(@PathParam("useruuid") String useruuid) {
        return statusService.getLatestEmploymentStatus(useruuid);
    }

    @POST
    @Path("/{useruuid}/statuses")
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(@PathParam("useruuid") String useruuid, UserStatus userStatus) {
        userStatus.setUseruuid(useruuid);
        statusService.create(userStatus);
        CreateUserStatusEvent event = new CreateUserStatusEvent(useruuid, userStatus);
        aggregateEventSender.handleEvent(event);
    }

    @DELETE
    @Path("/{useruuid}/status/{statusuuid}")
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("statusuuid") String statusuuid) {
        statusService.delete(statusuuid);
        DeleteUserStatusEvent event = new DeleteUserStatusEvent(useruuid, statusuuid);
        aggregateEventSender.handleEvent(event);
    }
}