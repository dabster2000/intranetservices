package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.dto.DocumentTemplateDTO;
import dk.trustworks.intranet.documentservice.dto.TemplatePlaceholderDTO;
import dk.trustworks.intranet.documentservice.dto.WordTemplateUploadRequest;
import dk.trustworks.intranet.documentservice.dto.WordTemplateUploadResponse;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import dk.trustworks.intranet.documentservice.model.enums.TemplateUsage;
import dk.trustworks.intranet.documentservice.security.TemplateAccessPolicy;
import dk.trustworks.intranet.documentservice.services.TemplatePlaceholderAiService;
import dk.trustworks.intranet.documentservice.services.TemplateService;
import dk.trustworks.intranet.utils.services.WordDocumentService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * REST resource for document template management.
 * Provides endpoints for CRUD operations on templates.
 */
@JBossLog
@Path("/templates")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"documents:read"})
public class TemplateResource {

    @Inject
    TemplateService templateService;

    @Inject
    WordDocumentService wordDocumentService;

    @Inject
    TemplatePlaceholderAiService templatePlaceholderAiService;

    @Inject
    TemplateAccessPolicy accessPolicy;

    /**
     * Get all templates.
     *
     * @param includeInactive Whether to include inactive templates
     * @param usage           Restricts the collection to one template usage
     * @param forUserUuid     Optional. The person the caller is preparing
     *                        documents for. When given, each template's default
     *                        signers are narrowed to that person's company —
     *                        the signers marked for every company plus that
     *                        company's own — so a merged template offers the
     *                        right counter-signers instead of all three
     *                        companies' executives. Omitting it returns every
     *                        signer, which is what the template editor wants.
     * @return List of templates
     */
    @GET
    public List<DocumentTemplateDTO> getAll(
            @QueryParam("includeInactive") @DefaultValue("false") boolean includeInactive,
            @QueryParam("usage") TemplateUsage usage,
            @QueryParam("forUserUuid") String forUserUuid) {
        accessPolicy.requireCollectionRead(usage);
        log.infof("GET /templates?includeInactive=%s&usage=%s&forUserUuid=%s", includeInactive, usage, forUserUuid);
        List<DocumentTemplateDTO> templates = forUserUuid == null || forUserUuid.isBlank()
                ? templateService.findAll(includeInactive)
                : templateService.findAllForUser(includeInactive, forUserUuid);
        return accessPolicy.filterCollection(templates, usage);
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
        accessPolicy.requireManager();
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
        DocumentTemplateDTO template = templateService.findByUuid(uuid);
        accessPolicy.requireTemplateRead(template);
        return template;
    }

    /**
     * Create a new template.
     *
     * @param dto Template DTO
     * @return Created template
     */
    @POST
    @RolesAllowed({"documents:write"})
    public Response create(@Valid DocumentTemplateDTO dto) {
        accessPolicy.requireManager();
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
    @RolesAllowed({"documents:write"})
    public DocumentTemplateDTO update(@PathParam("uuid") String uuid, @Valid DocumentTemplateDTO dto) {
        accessPolicy.requireManager();
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
    @RolesAllowed({"documents:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        accessPolicy.requireManager();
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
    @RolesAllowed({"documents:write"})
    public Response validate(@PathParam("uuid") String uuid, @Valid DocumentTemplateDTO dto) {
        accessPolicy.requireManager();
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
        return accessPolicy.requireActor();
    }

    // =========================================================================
    // Word Template File Management Endpoints
    // =========================================================================

    /**
     * Upload a Word template file for a template document.
     *
     * <p>The request body should contain:
     * <ul>
     *   <li>fileContent: Base64-encoded Word document (.docx)</li>
     *   <li>filename: Original filename</li>
     *   <li>documentUuid: UUID of the template document to associate with</li>
     * </ul>
     *
     * @param request Upload request with file content
     * @return Response with file UUID and extracted placeholders
     */
    @POST
    @Path("/documents/upload")
    @RolesAllowed({"documents:write"})
    public Response uploadWordTemplate(WordTemplateUploadRequest request) {
        accessPolicy.requireManager();
        log.infof("POST /templates/documents/upload: filename=%s, documentUuid=%s",
                request.filename(), request.documentUuid());

        try {
            // Decode base64 file content
            byte[] fileBytes = Base64.getDecoder().decode(request.fileContent());

            // Validate file is a Word document
            if (!isValidWordDocument(fileBytes)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Invalid file type. Please upload a Word document (.docx)\"}")
                        .build();
            }

            // Save to S3
            String fileUuid = wordDocumentService.saveWordTemplate(
                    fileBytes,
                    request.filename(),
                    request.documentUuid()
            );

            // Extract placeholders for immediate feedback
            Set<String> placeholders = wordDocumentService.extractPlaceholders(fileBytes);

            WordTemplateUploadResponse response = new WordTemplateUploadResponse(
                    fileUuid,
                    request.filename(),
                    fileBytes.length,
                    placeholders
            );

            log.infof("Uploaded Word template: uuid=%s, size=%d, placeholders=%d",
                    fileUuid, fileBytes.length, placeholders.size());

            return Response.status(Response.Status.CREATED).entity(response).build();

        } catch (IllegalArgumentException e) {
            log.errorf(e, "Invalid base64 content in upload request");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid file content (base64 decode failed)\"}")
                    .build();
        } catch (Exception e) {
            log.errorf(e, "Failed to upload Word template");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Download a Word template file.
     *
     * @param fileUuid UUID of the file in S3
     * @return Word document bytes
     */
    @GET
    @Path("/documents/{fileUuid}/download")
    @Produces("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public Response downloadWordTemplate(@PathParam("fileUuid") String fileUuid) {
        accessPolicy.requireDocumentRead(fileUuid);
        log.infof("GET /templates/documents/%s/download", fileUuid);

        try {
            byte[] fileBytes = wordDocumentService.getWordTemplate(fileUuid);
            String filename = wordDocumentService.getWordTemplateFilename(fileUuid);

            return Response.ok(fileBytes)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Length", fileBytes.length)
                    .build();

        } catch (WordDocumentService.WordDocumentException e) {
            log.errorf(e, "Word template not found: %s", fileUuid);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Template not found\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            log.errorf(e, "Failed to download Word template: %s", fileUuid);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /**
     * AI-suggest full placeholder definitions for a stored Word template:
     * labels, help texts, section groups and field types, ordered exactly
     * as the placeholders occur in the document. Suggestions only — the
     * caller (templates admin) reviews and saves them via the normal
     * template update. Falls back to deterministic document-order
     * definitions when the AI call fails, so this endpoint degrades
     * gracefully rather than erroring.
     *
     * @param fileUuid UUID of the file in S3
     * @return Ordered list of suggested placeholder definitions
     */
    @POST
    @Path("/documents/{fileUuid}/suggest-placeholders")
    @RolesAllowed({"documents:write"})
    public Response suggestPlaceholders(@PathParam("fileUuid") String fileUuid) {
        accessPolicy.requireManager();
        log.infof("POST /templates/documents/%s/suggest-placeholders", fileUuid);

        try {
            byte[] fileBytes = wordDocumentService.getWordTemplate(fileUuid);
            List<TemplatePlaceholderDTO> suggestions =
                    templatePlaceholderAiService.suggestPlaceholders(fileBytes);
            return Response.ok(suggestions).build();

        } catch (WordDocumentService.WordDocumentException e) {
            log.errorf(e, "Word template not found: %s", fileUuid);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Template not found\"}")
                    .build();
        } catch (Exception e) {
            log.errorf(e, "Failed to suggest placeholders: %s", fileUuid);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Extract placeholders from a Word template file stored in S3.
     *
     * @param fileUuid UUID of the file in S3
     * @return Set of placeholder keys found in the document
     */
    @GET
    @Path("/documents/{fileUuid}/placeholders")
    public Response extractPlaceholders(@PathParam("fileUuid") String fileUuid) {
        accessPolicy.requireDocumentRead(fileUuid);
        log.infof("GET /templates/documents/%s/placeholders", fileUuid);

        try {
            Set<String> placeholders = wordDocumentService.extractPlaceholders(fileUuid);
            return Response.ok(placeholders).build();

        } catch (WordDocumentService.WordDocumentException e) {
            log.errorf(e, "Word template not found: %s", fileUuid);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Template not found\"}")
                    .build();
        } catch (Exception e) {
            log.errorf(e, "Failed to extract placeholders: %s", fileUuid);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Extract placeholders from uploaded file bytes (without saving).
     * Useful for previewing placeholders before creating a template.
     *
     * @param request Request with base64-encoded file content
     * @return Set of placeholder keys found in the document
     */
    @POST
    @Path("/documents/extract-placeholders")
    @RolesAllowed({"documents:write"})
    public Response extractPlaceholdersFromContent(WordTemplateUploadRequest request) {
        accessPolicy.requireManager();
        log.infof("POST /templates/documents/extract-placeholders: filename=%s", request.filename());

        try {
            byte[] fileBytes = Base64.getDecoder().decode(request.fileContent());
            Set<String> placeholders = wordDocumentService.extractPlaceholders(fileBytes);
            return Response.ok(placeholders).build();

        } catch (IllegalArgumentException e) {
            log.errorf(e, "Invalid base64 content");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid file content\"}")
                    .build();
        } catch (Exception e) {
            log.errorf(e, "Failed to extract placeholders");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Delete a Word template file from S3.
     *
     * @param fileUuid UUID of the file to delete
     * @return No content response
     */
    @DELETE
    @Path("/documents/{fileUuid}")
    @RolesAllowed({"documents:write"})
    public Response deleteWordTemplate(@PathParam("fileUuid") String fileUuid) {
        accessPolicy.requireManager();
        log.infof("DELETE /templates/documents/%s", fileUuid);

        try {
            wordDocumentService.deleteWordTemplate(fileUuid);
            return Response.noContent().build();

        } catch (Exception e) {
            log.errorf(e, "Failed to delete Word template: %s", fileUuid);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Validates if the file bytes represent a valid Word document.
     * Checks for the ZIP signature (DOCX files are ZIP archives).
     */
    private boolean isValidWordDocument(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        // ZIP file signature: PK (0x50 0x4B 0x03 0x04)
        return bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04;
    }
}
