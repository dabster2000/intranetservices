package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.dto.TemplateClauseDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateClauseLinkDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateClausePlaceholderDTO;
import dk.trustworks.intranet.documentservice.model.ClauseAddendumShellEntity;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClauseVersionEntity;
import dk.trustworks.intranet.documentservice.security.TemplateAccessPolicy;
import dk.trustworks.intranet.documentservice.services.ClauseService;
import dk.trustworks.intranet.documentservice.services.TemplatePlaceholderAiService;
import dk.trustworks.intranet.utils.services.WordDocumentService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Base64;
import java.util.List;

/**
 * REST API for the clause library (template-clauses spec §4.1–§4.4, §6).
 * <p>
 * Clause management rides the template-admin posture: the service JWT
 * proves the BFF may call, {@code X-Requested-By} carries the human, and
 * every management endpoint requires HR/ADMIN via
 * {@link TemplateAccessPolicy}. The one preparer-facing read —
 * {@code GET /offered/{templateUuid}} — allows any identified employee
 * for employee-signing templates (the wizard's clause step), while
 * recruitment/dossier templates stay HR/ADMIN.
 */
@JBossLog
@Tag(name = "template-clauses", description = "Reusable, versioned clause library for signing templates")
@Path("/template-clauses")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"documents:read"})
public class ClauseResource {

    @Inject
    ClauseService clauseService;

    @Inject
    TemplateAccessPolicy accessPolicy;

    @Inject
    WordDocumentService wordDocumentService;

    @Inject
    TemplatePlaceholderAiService templatePlaceholderAiService;

    /** Fragment/shell upload body: base64 .docx + display metadata. */
    public record ClauseFileUploadRequest(String fileContent, String filename, String changeNote) {
    }

    // ---- Library management (HR/ADMIN) -----------------------------------------

    @GET
    @Operation(summary = "List all clauses (HR/ADMIN)")
    public List<TemplateClauseDTO> getAll() {
        accessPolicy.requireManager();
        return clauseService.findAll();
    }

    @GET
    @Path("/{uuid}")
    @Operation(summary = "One clause with parameters and version history (HR/ADMIN)")
    public TemplateClauseDTO getByUuid(@PathParam("uuid") String uuid) {
        accessPolicy.requireManager();
        return clauseService.findByUuid(uuid);
    }

    @POST
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Create a clause (HR/ADMIN)")
    public Response create(TemplateClauseDTO dto) {
        accessPolicy.requireManager();
        requireBody(dto);
        log.infof("POST /template-clauses: %s", dto.getName());
        return Response.status(Response.Status.CREATED).entity(clauseService.create(dto)).build();
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Update clause metadata and parameters (HR/ADMIN)")
    public TemplateClauseDTO update(@PathParam("uuid") String uuid, TemplateClauseDTO dto) {
        accessPolicy.requireManager();
        requireBody(dto);
        log.infof("PUT /template-clauses/%s", uuid);
        return clauseService.update(uuid, dto);
    }

    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Retire a clause (soft; a never-used draft is deleted) (HR/ADMIN)")
    public Response retire(@PathParam("uuid") String uuid) {
        accessPolicy.requireManager();
        log.infof("DELETE /template-clauses/%s", uuid);
        clauseService.retire(uuid);
        return Response.noContent().build();
    }

    // ---- Versions --------------------------------------------------------------

    @POST
    @Path("/{uuid}/versions")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Upload a new wording version (.docx fragment); returns the extracted tags (HR/ADMIN)")
    public Response addVersion(@PathParam("uuid") String uuid, ClauseFileUploadRequest request) {
        accessPolicy.requireManager();
        String actor = accessPolicy.requireActor();
        byte[] fragmentBytes = decodeDocx(request);
        log.infof("POST /template-clauses/%s/versions: %s (%d bytes) by %s",
                uuid, request.filename(), fragmentBytes.length, actor);
        ClauseService.UploadedVersion uploaded =
                clauseService.addVersion(uuid, fragmentBytes, request.filename(), request.changeNote(), actor);
        return Response.status(Response.Status.CREATED).entity(uploaded).build();
    }

    @POST
    @Path("/{uuid}/versions/{versionUuid}/publish")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Make a version the active wording — blocked when fragment tags and declared parameters disagree (HR/ADMIN)")
    public TemplateClauseDTO publishVersion(@PathParam("uuid") String uuid,
                                            @PathParam("versionUuid") String versionUuid) {
        accessPolicy.requireManager();
        String actor = accessPolicy.requireActor();
        log.infof("POST /template-clauses/%s/versions/%s/publish by %s", uuid, versionUuid, actor);
        return clauseService.publishVersion(uuid, versionUuid, actor);
    }

    @DELETE
    @Path("/{uuid}/versions/{versionUuid}")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Delete an unused, non-active version — versions sent with a case are immutable (HR/ADMIN)")
    public Response deleteVersion(@PathParam("uuid") String uuid, @PathParam("versionUuid") String versionUuid) {
        accessPolicy.requireManager();
        clauseService.deleteVersion(uuid, versionUuid);
        return Response.noContent().build();
    }

    @POST
    @Path("/{uuid}/versions/{versionUuid}/suggest-placeholders")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "AI-suggest parameter definitions (labels, types, source/sourceField) for a version's fragment (HR/ADMIN)")
    public List<TemplateClausePlaceholderDTO> suggestPlaceholders(@PathParam("uuid") String uuid,
                                                                  @PathParam("versionUuid") String versionUuid) {
        accessPolicy.requireManager();
        TemplateClauseVersionEntity version = TemplateClauseVersionEntity.findById(versionUuid);
        if (version == null || !uuid.equals(version.getClauseUuid())) {
            throw new WebApplicationException("Version not found on clause: " + versionUuid, 404);
        }
        byte[] fragmentBytes = wordDocumentService.getWordTemplate(version.getFileUuid());
        return templatePlaceholderAiService.suggestPlaceholders(fragmentBytes).stream()
                .filter(suggestion -> !ClauseService.CLAUSES_ANCHOR_KEY.equals(suggestion.getPlaceholderKey()))
                .map(suggestion -> TemplateClausePlaceholderDTO.builder()
                        .placeholderKey(suggestion.getPlaceholderKey())
                        .label(suggestion.getLabel())
                        .fieldType(suggestion.getFieldType())
                        .required(suggestion.isRequired())
                        .displayOrder(suggestion.getDisplayOrder())
                        .defaultValue(suggestion.getDefaultValue())
                        .helpText(suggestion.getHelpText())
                        .source(suggestion.getSource())
                        .sourceField(suggestion.getSourceField())
                        .fieldGroup(suggestion.getFieldGroup())
                        .validationRules(suggestion.getValidationRules())
                        .selectOptions(suggestion.getSelectOptions())
                        .build())
                .toList();
    }

    // ---- Template links --------------------------------------------------------

    @GET
    @Path("/links/{templateUuid}")
    @Operation(summary = "The clauses a template offers (HR/ADMIN)")
    public List<TemplateClauseLinkDTO> getLinks(@PathParam("templateUuid") String templateUuid) {
        accessPolicy.requireManager();
        return clauseService.findLinks(templateUuid);
    }

    @PUT
    @Path("/links/{templateUuid}")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Replace a template's offered-clause set — rejects placeholder-key collisions (HR/ADMIN)")
    public List<TemplateClauseLinkDTO> replaceLinks(@PathParam("templateUuid") String templateUuid,
                                                    List<TemplateClauseLinkDTO> links) {
        accessPolicy.requireManager();
        log.infof("PUT /template-clauses/links/%s: %d links", templateUuid, links == null ? 0 : links.size());
        return clauseService.replaceLinks(templateUuid, links);
    }

    // ---- Preparer-facing: offered clauses for a template -----------------------

    @GET
    @Path("/offered/{templateUuid}")
    @Operation(summary = "ACTIVE clauses offered on a template — the wizard/dossier clause step. "
            + "Identified employees may read for employee-signing templates; recruitment templates are HR/ADMIN")
    public List<ClauseService.OfferedClause> getOffered(@PathParam("templateUuid") String templateUuid) {
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(templateUuid);
        if (template == null) {
            throw new WebApplicationException("Template not found: " + templateUuid, 404);
        }
        if (accessPolicy.isRecruitmentTemplate(template)) {
            accessPolicy.requireManager();
        } else {
            accessPolicy.requireActor();
        }
        return clauseService.findOfferedForTemplate(templateUuid);
    }

    // ---- Shared tillæg shell ---------------------------------------------------

    @GET
    @Path("/addendum-shell")
    @Operation(summary = "The active shared tillæg shell, or 204 when the built-in fallback is in effect (HR/ADMIN)")
    public Response getAddendumShell() {
        accessPolicy.requireManager();
        return ClauseAddendumShellEntity.findActive()
                .map(shell -> Response.ok(shell).build())
                .orElseGet(() -> Response.noContent().build());
    }

    @PUT
    @Path("/addendum-shell")
    @RolesAllowed({"documents:write"})
    @Transactional
    @Operation(summary = "Upload/replace the shared tillæg shell (.docx with the {{CLAUSES}} anchor) (HR/ADMIN)")
    public ClauseAddendumShellEntity uploadAddendumShell(ClauseFileUploadRequest request) {
        accessPolicy.requireManager();
        byte[] shellBytes = decodeDocx(request);
        String documentText = wordDocumentService.extractDocumentText(shellBytes);
        if (!documentText.contains("{{" + ClauseService.CLAUSES_ANCHOR_KEY + "}}")
                && !documentText.contains("{{+" + ClauseService.CLAUSES_ANCHOR_KEY + "}}")) {
            throw new WebApplicationException(
                    "The tillæg shell must contain the {{" + ClauseService.CLAUSES_ANCHOR_KEY + "}} anchor"
                            + " — without it the clause points have nowhere to render", 400);
        }
        String fileUuid = wordDocumentService.saveWordTemplate(shellBytes, request.filename(), null);

        ClauseAddendumShellEntity shell = ClauseAddendumShellEntity.findActive()
                .orElseGet(ClauseAddendumShellEntity::new);
        shell.setFileUuid(fileUuid);
        shell.setOriginalFilename(request.filename());
        shell.setActive(true);
        shell.persist();
        log.infof("Tillæg shell replaced: %s (%d bytes)", request.filename(), shellBytes.length);
        return shell;
    }

    // ---- Helpers ---------------------------------------------------------------

    private static void requireBody(Object body) {
        if (body == null) {
            throw new WebApplicationException("Request body is required", 400);
        }
    }

    private static byte[] decodeDocx(ClauseFileUploadRequest request) {
        if (request == null || request.fileContent() == null || request.fileContent().isBlank()) {
            throw new WebApplicationException("File content is required", 400);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.fileContent());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Invalid file content (base64 decode failed)", 400);
        }
        // DOCX files are ZIP archives — check the PK signature.
        if (bytes.length < 4 || bytes[0] != 0x50 || bytes[1] != 0x4B || bytes[2] != 0x03 || bytes[3] != 0x04) {
            throw new WebApplicationException("Invalid file type — upload a Word document (.docx)", 400);
        }
        return bytes;
    }
}
