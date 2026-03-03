package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

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
    public Response create(@PathParam("useruuid") String useruuid, UserStatus userStatus) {
        userStatus.setUseruuid(useruuid);
        try {
            statusService.create(userStatus);
            return Response.noContent().build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{useruuid}/status/{statusuuid}")
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("statusuuid") String statusuuid) {
        statusService.delete(statusuuid);
    }
}