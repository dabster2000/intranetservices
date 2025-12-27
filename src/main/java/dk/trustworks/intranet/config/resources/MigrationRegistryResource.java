package dk.trustworks.intranet.config.resources;

import dk.trustworks.intranet.config.dto.MigrationRegistryResponse;
import dk.trustworks.intranet.config.dto.PageMigrationDto;
import dk.trustworks.intranet.config.model.PageMigration;
import dk.trustworks.intranet.config.repository.PageMigrationRepository;
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

/**
 * REST resource for the page migration registry.
 *
 * This endpoint provides the migration status of all pages to both
 * Vaadin and React frontends, enabling dynamic navigation routing
 * without app rebuilds.
 *
 * The GET endpoint is cached at the repository level with a 5-minute TTL.
 * Mutations (toggle) invalidate the cache to ensure consistency.
 */
@Tag(name = "Migration Registry", description = "Page migration status for React/Vaadin coexistence")
@Path("/system/migration-registry")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class MigrationRegistryResource {

    private final PageMigrationRepository repository;

    @Inject
    public MigrationRegistryResource(PageMigrationRepository repository) {
        this.repository = repository;
    }

    /**
     * Get the complete migration registry.
     *
     * Returns all pages with their migration status, ordered for display.
     * Results are cached for 5 minutes to minimize database queries.
     *
     * @return the migration registry response
     */
    @GET
    @Operation(
            summary = "Get migration registry",
            description = "Returns all pages with their migration status for menu rendering"
    )
    @APIResponse(responseCode = "200", description = "Registry retrieved successfully")
    public MigrationRegistryResponse getRegistry() {
        List<PageMigrationDto> pages = repository.findAllOrdered()
                .stream()
                .map(PageMigrationDto::fromEntity)
                .toList();

        // Use a simple version string based on count and last migrated timestamp
        String version = String.format("v%d", pages.size());

        return MigrationRegistryResponse.of(pages, version);
    }

    /**
     * Get migration status for a specific page.
     *
     * @param pageKey the page key (e.g., 'dashboard', 'timesheet')
     * @return the page migration DTO or 404 if not found
     */
    @GET
    @Path("/{pageKey}")
    @Operation(
            summary = "Get page migration status",
            description = "Returns migration status for a specific page"
    )
    @APIResponse(responseCode = "200", description = "Page found")
    @APIResponse(responseCode = "404", description = "Page not found")
    public Response getPage(
            @Parameter(description = "The page key", required = true)
            @PathParam("pageKey") String pageKey
    ) {
        Optional<PageMigration> page = repository.findByPageKey(pageKey);

        if (page.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        return Response.ok(PageMigrationDto.fromEntity(page.get())).build();
    }

    /**
     * Toggle migration status for a page.
     *
     * Switches a page between migrated (React) and non-migrated (Vaadin) status.
     * Requires ADMIN role. Invalidates the cache to ensure both frontends
     * see the change within the next request.
     *
     * @param pageKey the page key to toggle
     * @return the updated page migration or 404 if not found
     */
    @PUT
    @Path("/{pageKey}/toggle")
    @RolesAllowed({"ADMIN"})
    @Operation(
            summary = "Toggle page migration status",
            description = "Switches a page between migrated (React) and non-migrated (Vaadin) status"
    )
    @APIResponse(responseCode = "200", description = "Status toggled successfully")
    @APIResponse(responseCode = "404", description = "Page not found")
    public Response toggleMigration(
            @Parameter(description = "The page key to toggle", required = true)
            @PathParam("pageKey") String pageKey
    ) {
        Optional<PageMigration> updated = repository.toggleMigration(pageKey);

        if (updated.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        return Response.ok(PageMigrationDto.fromEntity(updated.get())).build();
    }

    /**
     * Set migration status for a page.
     *
     * Explicitly sets a page as migrated or non-migrated.
     * Requires ADMIN role.
     *
     * @param pageKey  the page key
     * @param migrated the new status (true = React, false = Vaadin)
     * @return the updated page migration or 404 if not found
     */
    @PUT
    @Path("/{pageKey}")
    @RolesAllowed({"ADMIN"})
    @Operation(
            summary = "Set page migration status",
            description = "Explicitly sets a page as migrated (React) or non-migrated (Vaadin)"
    )
    @APIResponse(responseCode = "200", description = "Status updated successfully")
    @APIResponse(responseCode = "404", description = "Page not found")
    public Response setMigrationStatus(
            @Parameter(description = "The page key", required = true)
            @PathParam("pageKey") String pageKey,
            @Parameter(description = "Migration status (true = React, false = Vaadin)", required = true)
            @QueryParam("migrated") boolean migrated
    ) {
        Optional<PageMigration> updated = repository.setMigrationStatus(pageKey, migrated);

        if (updated.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        return Response.ok(PageMigrationDto.fromEntity(updated.get())).build();
    }

    /**
     * Get only migrated pages (React-enabled).
     *
     * @return list of migrated pages
     */
    @GET
    @Path("/migrated")
    @Operation(
            summary = "Get migrated pages",
            description = "Returns only pages that have been migrated to React"
    )
    public List<PageMigrationDto> getMigratedPages() {
        return repository.findMigrated()
                .stream()
                .map(PageMigrationDto::fromEntity)
                .toList();
    }

    /**
     * Get only non-migrated pages (Vaadin-only).
     *
     * @return list of non-migrated pages
     */
    @GET
    @Path("/not-migrated")
    @Operation(
            summary = "Get non-migrated pages",
            description = "Returns only pages that are still in Vaadin"
    )
    public List<PageMigrationDto> getNotMigratedPages() {
        return repository.findNotMigrated()
                .stream()
                .map(PageMigrationDto::fromEntity)
                .toList();
    }
}
