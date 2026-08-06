package dk.trustworks.intranet.config.resources;

import dk.trustworks.intranet.config.AnonymousRouteTrees;
import dk.trustworks.intranet.config.PageRegistryValidation;
import dk.trustworks.intranet.config.dto.PageRegistryResponse;
import dk.trustworks.intranet.config.dto.PageRegistryDto;
import dk.trustworks.intranet.config.model.PageRegistry;
import dk.trustworks.intranet.config.repository.PageRegistryRepository;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition;
import dk.trustworks.intranet.security.AuthzAuditService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "Page Registry", description = "Page visibility and access configuration")
@Path("/system/page-registry")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"system:read"})
@SecurityRequirement(name = "jwt")
public class PageRegistryResource {

    private final PageRegistryRepository repository;
    private final AuthzAuditService authzAuditService;

    @Inject
    public PageRegistryResource(PageRegistryRepository repository, AuthzAuditService authzAuditService) {
        this.repository = repository;
        this.authzAuditService = authzAuditService;
    }

    /** Snapshot of the two authorization-bearing columns, for authz_audit before/after. */
    private static Map<String, Object> gateSnapshot(PageRegistry page) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("pageKey", page.getPageKey());
        snap.put("reactRoute", page.getReactRoute());
        snap.put("requiredRoles", page.getRequiredRoles());
        snap.put("requiredPermission", page.getRequiredPermission());
        return snap;
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
    @Transactional
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

        // Phase 6 (task 6.10): normalise case on write and refuse role names that do not
        // exist in this environment's role_definition — a value naming nothing would
        // silently make the page unreachable.
        Set<String> knownRoles = RoleDefinition.<RoleDefinition>listAll().stream()
                .map(RoleDefinition::getName)
                .collect(Collectors.toSet());
        PageRegistryValidation.RolesResult result =
                PageRegistryValidation.normalizeAndValidateRoles(roles, knownRoles);
        if (!result.valid()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Unknown roles: " + String.join(", ", result.unknown()) + "\"}")
                    .build();
        }

        Optional<PageRegistry> pageOpt = repository.findByPageKey(pageKey);
        if (pageOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        Map<String, Object> before = gateSnapshot(pageOpt.get());
        PageRegistry updated = repository.setRequiredRoles(pageKey, result.normalized()).orElseThrow();

        // Phase 7 (task 7.2): page-gate edits are authorization mutations — audited and
        // version-bumped in this same transaction.
        authzAuditService.record("PAGE_ROLES_CHANGED", "page_registry", pageKey,
                before, gateSnapshot(updated));

        return Response.ok(PageRegistryDto.fromEntity(updated)).build();
    }

    @PUT
    @Path("/{pageKey}/permission")
    @RolesAllowed({"system:write"})
    @Operation(summary = "Update page required permission",
            description = "Sets the permission gating page entry. An empty value clears it, falling back to required roles (dual-read until Phase 14).")
    @APIResponse(responseCode = "200", description = "Permission updated successfully")
    @APIResponse(responseCode = "400", description = "Permission key not in the catalogue")
    @APIResponse(responseCode = "404", description = "Page not found")
    @APIResponse(responseCode = "409", description = "Page belongs to an anonymous flow and cannot be permission-gated")
    @Transactional
    public Response setRequiredPermission(
            @Parameter(description = "The page key", required = true)
            @PathParam("pageKey") String pageKey,
            @Parameter(description = "Permission key from the catalogue (e.g. invoices:write); empty clears")
            @QueryParam("permission") String permission
    ) {
        String value = null;
        if (permission != null && !permission.isBlank()) {
            // Validated against the code catalogue (identical in every environment — never
            // against role_definition, which diverges per environment). The V467 FK is the
            // database-level backstop; this check exists to return a 400 instead of a 500.
            PageRegistryValidation.PermissionResult result =
                    PageRegistryValidation.normalizeAndValidatePermission(permission);
            if (!result.valid()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Unknown permission: " + result.normalized() + "\"}")
                        .build();
            }
            value = result.normalized();
        }

        Optional<PageRegistry> pageOpt = repository.findByPageKey(pageKey);
        if (pageOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Page not found: " + pageKey + "\"}")
                    .build();
        }

        // Phase 7 hard block (owner decision 2026-08-06): a page inside an anonymous
        // flow's tree (apply, consent, guest kiosk, onboarding, login, mobile expenses
        // PWA) can never be permission-gated from here. Gating one breaks the flow
        // outright for external users; un-gating stays a code change. Clearing (NULL)
        // is equally refused so the rows stay untouched by this endpoint entirely.
        if (AnonymousRouteTrees.isAnonymousTree(pageOpt.get().getReactRoute())) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\": \"Page '" + pageKey + "' (" + pageOpt.get().getReactRoute()
                            + ") belongs to an anonymous flow (candidate apply, consent, guest kiosk,"
                            + " onboarding upload, or the mobile expenses PWA). Its access is frozen by"
                            + " the Phase 1 public allowlist and cannot be changed from the admin"
                            + " console — see src/lib/auth/publicRoutes.ts in the frontend repo.\"}")
                    .build();
        }

        Map<String, Object> before = gateSnapshot(pageOpt.get());
        PageRegistry updated = repository.setRequiredPermission(pageKey, value).orElseThrow();

        // Phase 7 (task 7.2): audited and version-bumped in this same transaction.
        authzAuditService.record("PAGE_PERMISSION_CHANGED", "page_registry", pageKey,
                before, gateSnapshot(updated));

        return Response.ok(PageRegistryDto.fromEntity(updated)).build();
    }
}
