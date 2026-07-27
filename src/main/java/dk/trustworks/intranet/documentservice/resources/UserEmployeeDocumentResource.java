package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.dto.EmployeeDocumentDTO;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.StoreCommand;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User-scoped surface of the employee document store (spec §6.4):
 * list + upload under {@code /users/{useruuid}/employee-documents}.
 *
 * <p>Authorization model: the system JWT satisfies the class-level scopes
 * (client-level gate); the BFF is the user-level gate and passes
 * {@code includeHrOnly} only when the caller is not the subject (the
 * established X-Requested-By architecture — spec §6.9). The
 * {@code X-Requested-By} actor is recorded on uploads and audit rows.</p>
 */
@JBossLog
@RequestScoped
@Path("/users/{useruuid}/employee-documents")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"documents:read"})
public class UserEmployeeDocumentResource {

    @Inject
    EmployeeDocumentService employeeDocumentService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * List a user's documents (metadata only, never bytes).
     *
     * @param includeHrOnly   BFF-set trust flag: true only for the HR view
     * @param includeArchived include archived documents (HR "show archived" toggle)
     */
    @GET
    public List<EmployeeDocumentDTO> list(@PathParam("useruuid") String useruuid,
                                          @QueryParam("includeHrOnly") @DefaultValue("false") boolean includeHrOnly,
                                          @QueryParam("includeArchived") @DefaultValue("false") boolean includeArchived) {
        List<EmployeeDocument> docs = employeeDocumentService.list(useruuid, includeHrOnly, includeArchived);
        return toDTOs(docs);
    }

    /**
     * Upload a document into the user's file. Multipart form
     * ({@code file}, optional {@code category}, {@code label},
     * {@code hrOnly}, {@code selfUpload}).
     *
     * <p>{@code selfUpload=true} forces {@code source=MANUAL_SELF},
     * {@code needs_review=1} and ignores {@code hrOnly} (an employee can
     * never hide a document from HR — spec §6.4).</p>
     */
    @POST
    @RolesAllowed({"documents:write"})
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(@PathParam("useruuid") String useruuid,
                           @RestForm("file") FileUpload file,
                           @RestForm("category") String categoryRaw,
                           @RestForm("label") String label,
                           @RestForm("hrOnly") @DefaultValue("false") boolean hrOnly,
                           @RestForm("selfUpload") @DefaultValue("false") boolean selfUpload) {
        if (file == null) {
            throw new BadRequestException("Missing multipart part 'file'");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.uploadedFile());
        } catch (IOException e) {
            throw new BadRequestException("Could not read uploaded file");
        }

        EmployeeDocumentCategory category = parseCategory(categoryRaw);
        String actor = requestHeaderHolder.getUserUuid();

        EmployeeDocument doc = employeeDocumentService.store(StoreCommand.upload(
                useruuid, bytes, file.fileName(), file.contentType(),
                category, label, hrOnly, selfUpload, actor));

        return Response.status(Response.Status.CREATED)
                .entity(EmployeeDocumentDTO.from(doc, resolveName(actor)))
                .build();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static EmployeeDocumentCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return EmployeeDocumentCategory.OTHER;
        try {
            return EmployeeDocumentCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown category: " + raw);
        }
    }

    static List<EmployeeDocumentDTO> toDTOs(List<EmployeeDocument> docs) {
        Map<String, String> nameCache = new HashMap<>();
        return docs.stream()
                .map(d -> EmployeeDocumentDTO.from(d,
                        d.getUploadedBy() == null ? null
                                : nameCache.computeIfAbsent(d.getUploadedBy(),
                                        UserEmployeeDocumentResource::lookupName)))
                .toList();
    }

    static String resolveName(String userUuid) {
        return userUuid == null ? null : lookupName(userUuid);
    }

    private static String lookupName(String userUuid) {
        User user = User.findById(userUuid);
        if (user == null) return null;
        String name = ((user.getFirstname() == null ? "" : user.getFirstname()) + " "
                + (user.getLastname() == null ? "" : user.getLastname())).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }
}
