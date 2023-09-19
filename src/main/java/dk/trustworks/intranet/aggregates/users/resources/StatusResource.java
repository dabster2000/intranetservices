package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.commands.AggregateCommand;
import dk.trustworks.intranet.aggregates.users.events.CreateUserStatusEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteUserStatusEvent;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

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
    AggregateCommand aggregateCommand;

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
    public void create(@PathParam("useruuid") String useruuid, UserStatus status) {
        status.setUseruuid(useruuid);
        CreateUserStatusEvent event = new CreateUserStatusEvent(useruuid, status);
        aggregateCommand.handleEvent(event);
    }

    @DELETE
    @Path("/{useruuid}/status/{statusuuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("statusuuid") String statusuuid) {
        DeleteUserStatusEvent event = new DeleteUserStatusEvent(useruuid, statusuuid);
        aggregateCommand.handleEvent(event);
    }
}