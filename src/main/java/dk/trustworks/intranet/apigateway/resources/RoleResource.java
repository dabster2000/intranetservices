package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.userservice.services.RoleService;
import dk.trustworks.intranet.userservice.services.RoleService.RoleAssignmentRailException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@RolesAllowed({"teams:read"})
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
    @RolesAllowed({"teams:write"})
    public void create(@PathParam("uuid") String useruuid, @Valid Role role) {
        roleService.create(useruuid, role);
    }

    @DELETE
    @Path("/{uuid}/roles")
    @Operation(summary = "Delete all user roles")
    public Response delete(@PathParam("uuid") String useruuid) {
        try {
            roleService.delete(useruuid);
            return Response.noContent().build();
        } catch (RoleAssignmentRailException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @DELETE
    @Path("/{uuid}/roles/{roleuuid}")
    @Operation(summary = "Delete a specific user role")
    public Response delete(@PathParam("uuid") String useruuid, @PathParam("roleuuid") String roleuuid) {
        // Routed through the service so the 7.9 rails run and the removal is audited
        // and version-bumped in one transaction (a bare Role.deleteById here previously
        // bypassed the Phase 4 version bump entirely — latent defect, see findings).
        try {
            roleService.deleteByRoleUuid(useruuid, roleuuid);
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (RoleAssignmentRailException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }
}