package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.services.StatusService;
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
@RolesAllowed({"SYSTEM", "USER", "EXTERNAL", "EDITOR", "CXO", "SALES", "VTV", "ACCOUNTING", "MANAGER", "PARTNER", "ADMIN"})
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
    @RolesAllowed({"CXO", "PARTNER", "ADMIN"})
    public void create(@PathParam("useruuid") String useruuid, UserStatus status) {
        statusService.create(useruuid, status);
    }

    @DELETE
    @Path("/{useruuid}/statuses/{statusuuid}")
    @RolesAllowed({"CXO", "PARTNER", "ADMIN"})
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("statusuuid") String statusuuid) {
        statusService.delete(useruuid, statusuuid);
    }
}