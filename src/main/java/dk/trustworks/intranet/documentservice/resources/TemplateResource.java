package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.dto.DocumentTemplateDTO;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import dk.trustworks.intranet.documentservice.services.TemplateService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * REST resource for document template management.
 * Provides endpoints for CRUD operations on templates.
 */
@JBossLog
@Path("/templates")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
public class TemplateResource {

    @Inject
    TemplateService templateService;

    @Context
    SecurityContext securityContext;

    /**
     * Get all templates.
     *
     * @param includeInactive Whether to include inactive templates
     * @return List of templates
     */
    @GET
    public List<DocumentTemplateDTO> getAll(@QueryParam("includeInactive") @DefaultValue("false") boolean includeInactive) {
        log.infof("GET /templates?includeInactive=%s", includeInactive);
        return templateService.findAll(includeInactive);
    }

    /**
     * Get templates by category.
     *
     * @param category The template category
     * @return List of templates for the category
     */
    @GET
    @Path("/category/{category}")
    public List<DocumentTemplateDTO> getByCategory(@PathParam("category") TemplateCategory category) {
        log.infof("GET /templates/category/%s", category);
        return templateService.findByCategory(category);
    }

    /**
     * Get a template by UUID.
     *
     * @param uuid The template UUID
     * @return Template with placeholders
     */
    @GET
    @Path("/{uuid}")
    public DocumentTemplateDTO getByUuid(@PathParam("uuid") String uuid) {
        log.infof("GET /templates/%s", uuid);
        return templateService.findByUuid(uuid);
    }

    /**
     * Create a new template.
     *
     * @param dto Template DTO
     * @return Created template
     */
    @POST
    public Response create(@Valid DocumentTemplateDTO dto) {
        String currentUser = getCurrentUserUuid();
        log.infof("POST /templates by user %s: %s", currentUser, dto.getName());

        DocumentTemplateDTO created = templateService.create(dto, currentUser);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    /**
     * Update an existing template.
     *
     * @param uuid Template UUID
     * @param dto Template DTO
     * @return Updated template
     */
    @PUT
    @Path("/{uuid}")
    public DocumentTemplateDTO update(@PathParam("uuid") String uuid, @Valid DocumentTemplateDTO dto) {
        String currentUser = getCurrentUserUuid();
        log.infof("PUT /templates/%s by user %s", uuid, currentUser);
        log.infof("  -> DTO name: %s, category: %s", dto.getName(), dto.getCategory());
        log.infof("  -> DTO placeholders: %s (count: %d)",
            dto.getPlaceholders() != null ? "present" : "NULL",
            dto.getPlaceholders() != null ? dto.getPlaceholders().size() : 0);
        if (dto.getPlaceholders() != null) {
            dto.getPlaceholders().forEach(p ->
                log.infof("     - Placeholder: key=%s, label=%s, uuid=%s",
                    p.getPlaceholderKey(), p.getLabel(), p.getUuid()));
        }

        return templateService.update(uuid, dto, currentUser);
    }

    /**
     * Delete a template (soft delete).
     *
     * @param uuid Template UUID
     * @return No content response
     */
    @DELETE
    @Path("/{uuid}")
    public Response delete(@PathParam("uuid") String uuid) {
        String currentUser = getCurrentUserUuid();
        log.infof("DELETE /templates/%s by user %s", uuid, currentUser);

        templateService.delete(uuid);
        return Response.noContent().build();
    }

    /**
     * Validate a template without saving it.
     *
     * @param dto Template DTO to validate
     * @return Validation result
     */
    @POST
    @Path("/{uuid}/validate")
    public Response validate(@PathParam("uuid") String uuid, @Valid DocumentTemplateDTO dto) {
        log.infof("POST /templates/%s/validate", uuid);

        try {
            templateService.validateTemplate(dto);
            return Response.ok().entity("{\"valid\":true,\"message\":\"Template is valid\"}").build();
        } catch (WebApplicationException e) {
            return Response.status(e.getResponse().getStatus())
                    .entity("{\"valid\":false,\"message\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Get the current user's UUID from the security context.
     *
     * @return User UUID or "system" if not available
     */
    private String getCurrentUserUuid() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return "system";
    }
}
