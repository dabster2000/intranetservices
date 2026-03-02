package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.entity.UserPersonalDetails;
import dk.trustworks.intranet.userservice.services.UserPersonalDetailsService;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
public class UserPersonalDetailsResource {

    @Inject
    UserPersonalDetailsService userPersonalDetailsService;

    @GET
    @Path("/{useruuid}/personaldetails")
    public List<UserPersonalDetails> findAll(@PathParam("useruuid") String useruuid) {
        return userPersonalDetailsService.findAll(useruuid);
    }

    @GET
    @Path("/{useruuid}/personaldetails/current")
    public UserPersonalDetails findCurrent(@PathParam("useruuid") String useruuid) {
        return userPersonalDetailsService.findCurrent(useruuid);
    }

    @POST
    @Path("/{useruuid}/personaldetails")
    @RolesAllowed({"SYSTEM", "ADMIN"})
    public Response create(@PathParam("useruuid") String useruuid,
                           @Valid @NotNull UserPersonalDetails details) {
        UserPersonalDetails created = userPersonalDetailsService.create(useruuid, details);
        return Response.created(URI.create("/users/" + useruuid + "/personaldetails/" + created.getUuid()))
                .entity(created)
                .build();
    }

    @PUT
    @Path("/{useruuid}/personaldetails/{uuid}")
    @RolesAllowed({"SYSTEM", "ADMIN"})
    public Response update(@PathParam("useruuid") String useruuid,
                           @PathParam("uuid") String uuid,
                           @Valid @NotNull UserPersonalDetails details) {
        UserPersonalDetails updated = userPersonalDetailsService.update(uuid, details);
        return Response.ok(updated).build();
    }

    @DELETE
    @Path("/{useruuid}/personaldetails/{uuid}")
    @RolesAllowed({"SYSTEM", "ADMIN"})
    public Response delete(@PathParam("useruuid") String useruuid,
                           @PathParam("uuid") String uuid) {
        userPersonalDetailsService.delete(uuid);
        return Response.noContent().build();
    }
}
