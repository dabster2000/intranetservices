package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import dk.trustworks.intranet.userservice.services.UserContactInfoService;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

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
    public UserContactinfo findOne(@PathParam("useruuid") String useruuid) {
        return userContactInfoService.findOne(useruuid);
    }

    @PUT
    @Path("/{useruuid}/contactinfo")
    public void update(@PathParam("useruuid") String useruuid, UserContactinfo userContactinfo) {
        userContactInfoService.update(useruuid, userContactinfo);
    }
}