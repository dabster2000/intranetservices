package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.userservice.model.UserContactinfo;
import dk.trustworks.intranet.userservice.services.UserContactInfoService;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@RolesAllowed({"USER", "EXTERNAL"})
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