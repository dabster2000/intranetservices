package dk.trustworks.intranet.config.resources;

import dk.trustworks.intranet.config.dto.PageRegistryResponse;
import dk.trustworks.intranet.config.dto.PageRegistryDto;
import dk.trustworks.intranet.config.model.PageRegistry;
import dk.trustworks.intranet.config.repository.PageRegistryRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;

@Tag(name = "Page Registry", description = "Page visibility and access configuration")
@Path("/system/page-registry")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"system:read"})
@SecurityRequirement(name = "jwt")
public class PageRegistryResource {

    private final PageRegistryRepository repository;

    @Inject
    public PageRegistryResource(PageRegistryRepository repository) {
        this.repository = repository;
    }

    @GET
    @Operation(summary = "Get page registry", description = "Returns all pages with visibility and role configuration")
    @APIResponse(responseCode = "200", description = "Registry retrieved successfully")
    public PageRegistryResponse getRegistry() {
        List<PageRegistryDto> pages = repository.findAllOrdered()
                .stream()
                .map(PageRegistryDto::fromEntity)
                .toList();

        String version = String.format("v%d", pages.size());

        return PageRegistryResponse.of(pages, version);
    }

    @GET
    @Path("/{pageKey}")
    @Operation(summary = "Get page configuration", description = "Returns configuration for a specific page")
    @APIResponse(responseCode = "200", description = "Page found")
    @APIResponse(responseCode = "404", description = "Page not found")
    public Response getPage(
            @Parameter(description = "The page key", required = true)
            @PathParam("pageKey") String pageKey
    ) {
        Optional<PageRegistry> page = repository.findByPageKey(pageKey);

        if (page.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        return Response.ok(PageRegistryDto.fromEntity(page.get())).build();
    }

    @PUT
    @Path("/{pageKey}/visibility")
    @RolesAllowed({"system:write"})
    @Operation(summary = "Toggle page visibility", description = "Switches a page between visible and hidden in the menu")
    @APIResponse(responseCode = "200", description = "Visibility toggled successfully")
    @APIResponse(responseCode = "404", description = "Page not found")
    public Response toggleVisibility(
            @Parameter(description = "The page key to toggle", required = true)
            @PathParam("pageKey") String pageKey
    ) {
        Optional<PageRegistry> updated = repository.toggleVisibility(pageKey);

        if (updated.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        return Response.ok(PageRegistryDto.fromEntity(updated.get())).build();
    }

    @PUT
    @Path("/{pageKey}")
    @RolesAllowed({"system:write"})
    @Operation(summary = "Set page visibility", description = "Explicitly sets a page as visible or hidden")
    @APIResponse(responseCode = "200", description = "Visibility updated successfully")
    @APIResponse(responseCode = "404", description = "Page not found")
    public Response setVisibility(
            @Parameter(description = "The page key", required = true)
            @PathParam("pageKey") String pageKey,
            @Parameter(description = "Visibility status (true = visible in menu)", required = true)
            @QueryParam("visible") boolean visible
    ) {
        Optional<PageRegistry> updated = repository.setVisibility(pageKey, visible);

        if (updated.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        return Response.ok(PageRegistryDto.fromEntity(updated.get())).build();
    }

    @PUT
    @Path("/{pageKey}/roles")
    @RolesAllowed({"system:write"})
    @Operation(summary = "Update page required roles", description = "Sets the required roles for a page")
    @APIResponse(responseCode = "200", description = "Roles updated successfully")
    @APIResponse(responseCode = "404", description = "Page not found")
    public Response setRequiredRoles(
            @Parameter(description = "The page key", required = true)
            @PathParam("pageKey") String pageKey,
            @Parameter(description = "Comma-separated roles (e.g., HR,ADMIN)", required = true)
            @QueryParam("roles") String roles
    ) {
        if (roles == null || roles.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"roles parameter is required\"}")
                    .build();
        }

        Optional<PageRegistry> updated = repository.setRequiredRoles(pageKey, roles.trim());

        if (updated.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        return Response.ok(PageRegistryDto.fromEntity(updated.get())).build();
    }
}
