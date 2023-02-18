package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.userservice.model.Role;
import dk.trustworks.intranet.userservice.services.RoleService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.validation.Valid;
import javax.ws.rs.*;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@RolesAllowed({"USER", "EXTERNAL"})
@SecurityRequirement(name = "jwt")
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class RoleResource {

    @Inject
    RoleService roleService;

    @GET
    @Path("/{uuid}/roles")
    public List<Role> listAll(@PathParam("uuid") String useruuid) {
        return roleService.listAll(useruuid);
    }

    @POST
    @Path("/{uuid}/roles")
    @RolesAllowed({"CXO", "PARTNER", "ADMIN"})
    public void create(@PathParam("uuid") String useruuid, @Valid Role role) {
        roleService.create(useruuid, role);
    }

    @DELETE
    @Path("/{uuid}/roles")
    @RolesAllowed({"CXO", "PARTNER", "ADMIN"})
    @Operation(summary = "Delete all user roles")
    public void delete(@PathParam("uuid") String useruuid) {
        roleService.delete(useruuid);
    }
}