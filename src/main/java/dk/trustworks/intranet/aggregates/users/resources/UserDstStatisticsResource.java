package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.users.services.UserDstStatisticsService;
import dk.trustworks.intranet.domain.user.entity.UserDstStatistic;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@JBossLog
@Tag(name = "user")
@Path("/users")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class UserDstStatisticsResource {

    @Inject
    UserDstStatisticsService service;

    @GET
    @Path("/{useruuid}/dststatistics")
    public List<UserDstStatistic> listAll(@PathParam("useruuid") String useruuid) {
        return service.findByUseruuid(useruuid);
    }

    @POST
    @Path("/{useruuid}/dststatistics")
    public void create(@PathParam("useruuid") String useruuid, UserDstStatistic entity) {
        System.out.println("UserDstStatisticsResource.create");
        System.out.println("useruuid = " + useruuid + ", entity = " + entity);
        entity.setUseruuid(useruuid);
        service.create(entity);
    }

    @DELETE
    @Path("/{useruuid}/dststatistics/{uuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("uuid") String entityuuid) {
        service.delete(entityuuid);
    }
}