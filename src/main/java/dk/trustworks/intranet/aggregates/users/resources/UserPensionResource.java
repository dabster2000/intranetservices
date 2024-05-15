package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.services.UserPensionService;
import dk.trustworks.intranet.userservice.model.UserPension;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@JBossLog
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class UserPensionResource {


    @Inject
    UserPensionService service;

    @Inject
    AggregateEventSender aggregateEventSender;


    @GET
    @Path("/{useruuid}/pensions")
    public List<UserPension> listAll(@PathParam("useruuid") String useruuid) {
        return service.findByUseruuid(useruuid);
    }

    @POST
    @Path("/{useruuid}/pensions")
    public void create(@PathParam("useruuid") String useruuid, UserPension entity) {
        entity.setUseruuid(useruuid);
        service.create(entity);
    }

    @DELETE
    @Path("/{useruuid}/pensions/{uuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("uuid") String entityuuid) {
        service.delete(entityuuid);
    }
}