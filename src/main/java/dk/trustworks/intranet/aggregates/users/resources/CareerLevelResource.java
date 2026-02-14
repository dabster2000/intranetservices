package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.users.services.CareerLevelService;
import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class CareerLevelResource {

    @Inject
    CareerLevelService careerLevelService;

    @GET
    @Path("/{useruuid}/careerlevels")
    public List<UserCareerLevel> listAll(@PathParam("useruuid") String useruuid) {
        return careerLevelService.listAll(useruuid);
    }

    @GET
    @Path("/{useruuid}/careerlevels/current")
    public UserCareerLevel getCurrent(@PathParam("useruuid") String useruuid) {
        return careerLevelService.getCurrent(useruuid).orElse(null);
    }

    @POST
    @Path("/{useruuid}/careerlevels")
    public void create(@PathParam("useruuid") String useruuid, UserCareerLevel careerLevel) {
        careerLevel.setUseruuid(useruuid);
        careerLevelService.create(careerLevel);
    }

    @DELETE
    @Path("/{useruuid}/careerlevels/{uuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("uuid") String uuid) {
        careerLevelService.delete(uuid);
    }
}
