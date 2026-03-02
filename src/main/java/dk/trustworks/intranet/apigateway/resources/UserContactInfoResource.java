package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import dk.trustworks.intranet.userservice.services.UserContactInfoService;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class UserContactInfoResource {

    @Inject
    UserContactInfoService userContactInfoService;

    @GET
    @Path("/{useruuid}/contactinfo")
    public List<UserContactinfo> findAll(@PathParam("useruuid") String useruuid) {
        return userContactInfoService.findAll(useruuid);
    }

    @GET
    @Path("/{useruuid}/contactinfo/current")
    public UserContactinfo findCurrent(@PathParam("useruuid") String useruuid) {
        return userContactInfoService.findOne(useruuid);
    }

    @POST
    @Path("/{useruuid}/contactinfo")
    public Response create(@PathParam("useruuid") String useruuid, UserContactinfo userContactinfo) {
        UserContactinfo created = userContactInfoService.create(useruuid, userContactinfo);
        return Response.created(URI.create("/users/" + useruuid + "/contactinfo"))
                .entity(created)
                .build();
    }

    @PUT
    @Path("/{useruuid}/contactinfo")
    public void update(@PathParam("useruuid") String useruuid, UserContactinfo userContactinfo) {
        userContactInfoService.update(useruuid, userContactinfo);
    }
}
