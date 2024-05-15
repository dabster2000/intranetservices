package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.services.TransportationRegistrationService;
import dk.trustworks.intranet.userservice.model.TransportationRegistration;
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
public class TransportationRegistrationResource {


    @Inject
    TransportationRegistrationService service;

    @Inject
    AggregateEventSender aggregateEventSender;


    @GET
    @Path("/{useruuid}/transportation/registrations")
    public List<TransportationRegistration> listAll(@PathParam("useruuid") String useruuid) {
        return service.findByUseruuid(useruuid);
    }

    @POST
    @Path("/{useruuid}/transportation/registrations")
    public void create(@PathParam("useruuid") String useruuid, TransportationRegistration entity) {
        entity.setUseruuid(useruuid);
        service.create(entity);
    }

    @DELETE
    @Path("/{useruuid}/transportation/registrations/{uuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("uuid") String entityuuid) {
        service.delete(entityuuid);
    }


}