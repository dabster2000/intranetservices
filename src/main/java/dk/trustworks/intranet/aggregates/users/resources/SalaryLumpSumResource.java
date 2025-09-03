package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.services.SalaryLumpSumService;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
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
public class SalaryLumpSumResource {


    @Inject
    SalaryLumpSumService service;

    @Inject
    AggregateEventSender aggregateEventSender;


    @GET
    @Path("/{useruuid}/salarylumpsums")
    public List<SalaryLumpSum> listAll(@PathParam("useruuid") String useruuid) {
        return service.findByUseruuid(useruuid);
    }

    @POST
    @Path("/{useruuid}/salarylumpsums")
    public void create(@PathParam("useruuid") String useruuid, SalaryLumpSum entity) {
        entity.setUseruuid(useruuid);
        service.create(entity);
    }

    @DELETE
    @Path("/{useruuid}/salarylumpsums/{uuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("uuid") String uuid) {
        service.delete(uuid);
    }
}