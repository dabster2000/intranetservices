package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.userservice.services.RoleService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@RolesAllowed({"SYSTEM"})
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
    public void create(@PathParam("uuid") String useruuid, @Valid Role role) {
        roleService.create(useruuid, role);
    }

    @DELETE
    @Path("/{uuid}/roles")
    @Operation(summary = "Delete all user roles")
    public void delete(@PathParam("uuid") String useruuid) {
        roleService.delete(useruuid);
    }

    @DELETE
    @Path("/{uuid}/roles/{roleuuid}")
    @Operation(summary = "Delete a specifik user role")
    @Transactional
    public void delete(@PathParam("uuid") String useruuid, @PathParam("roleuuid") String roleuuid) {
        Role.deleteById(roleuuid);
    }
}