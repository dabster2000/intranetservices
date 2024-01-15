package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.services.TeamRoleService;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
public class TeamRoleResource {

    @Inject
    TeamRoleService teamRoleService;

    @GET
    @Path("/{useruuid}/teamroles")
    public List<TeamRole> getUserTeamRoles(@PathParam("useruuid") String useruuid) {
        return teamRoleService.listAll(useruuid);
    }
}