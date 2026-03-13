package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.dto.CreateRoleDefinitionRequest;
import dk.trustworks.intranet.domain.user.dto.RoleDefinitionDTO;
import dk.trustworks.intranet.domain.user.dto.UpdateRoleDefinitionRequest;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition.RoleInUseException;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition.SystemRoleModificationException;
import dk.trustworks.intranet.domain.user.service.RoleDefinitionService;
import dk.trustworks.intranet.domain.user.service.RoleDefinitionService.RoleAlreadyExistsException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * REST resource for managing role definitions.
 * All endpoints require SYSTEM role (invoked by the BFF with a system JWT).
 */
@Tag(name = "role-definitions")
@Path("/role-definitions")
@RequestScoped
@RolesAllowed({"teams:read"})
@SecurityRequirement(name = "jwt")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RoleDefinitionResource {

    @Inject
    RoleDefinitionService roleDefinitionService;

    @GET
    @Operation(summary = "List all role definitions with usage counts")
    public List<RoleDefinitionDTO> listAll() {
        return roleDefinitionService.listAll();
    }

    @GET
    @Path("/{name}")
    @Operation(summary = "Get a single role definition by name")
    public Response getByName(@PathParam("name") String name) {
        return roleDefinitionService.findByName(name)
                .map(dto -> Response.ok(dto).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Role definition not found: " + name + "\"}")
                        .build());
    }

    @POST
    @Operation(summary = "Create a new role definition")
    @RolesAllowed({"teams:write"})
    public Response create(@Valid @NotNull CreateRoleDefinitionRequest request) {
        try {
            var created = roleDefinitionService.create(request);
            return Response.created(URI.create("/role-definitions/" + created.name()))
                    .entity(created)
                    .build();
        } catch (RoleAlreadyExistsException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @PUT
    @Path("/{name}")
    @Operation(summary = "Update a role definition's display label")
    @RolesAllowed({"teams:write"})
    public Response update(@PathParam("name") String name,
                           @Valid @NotNull UpdateRoleDefinitionRequest request) {
        try {
            var updated = roleDefinitionService.update(name, request);
            return Response.ok(updated).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (SystemRoleModificationException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Delete a role definition")
    @RolesAllowed({"teams:write"})
    public Response delete(@PathParam("name") String name) {
        try {
            roleDefinitionService.delete(name);
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (SystemRoleModificationException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (RoleInUseException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }
}
