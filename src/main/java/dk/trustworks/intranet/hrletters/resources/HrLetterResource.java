package dk.trustworks.intranet.hrletters.resources;

import dk.trustworks.intranet.hrletters.dto.ApproveHrLetterRequest;
import dk.trustworks.intranet.hrletters.dto.DismissHrLetterRequest;
import dk.trustworks.intranet.hrletters.dto.HrLetterDTO;
import dk.trustworks.intranet.hrletters.dto.VacationTransferRequestDTO;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterStatus;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterType;
import dk.trustworks.intranet.hrletters.services.HrLetterService;
import dk.trustworks.intranet.security.ScopeGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * HR letters: signature-less salary-regulation notices and
 * vacation-transfer agreements (see {@code V528__Hr_letters.sql}).
 *
 * <p>Authorization model (mirrors the signing/employee-documents
 * surfaces): the system JWT satisfies the class-level scopes; the human
 * gates are enforced here — console reads and approvals require an actor
 * with unbounded {@code salaries:read} reach (HR/ADMIN — the BFF gates the
 * same way), while the self-service endpoints act strictly on the
 * {@code X-Requested-By} actor's own rows.</p>
 */
@JBossLog
@Tag(name = "hr-letters")
@Path("/hr-letters")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"signing:read"})
public class HrLetterResource {

    @Inject
    HrLetterService hrLetterService;

    @Inject
    ScopeGuard scopeGuard;

    // ── HR console ─────────────────────────────────────────────────────────

    @GET
    public List<HrLetterDTO> list(@QueryParam("status") String status,
                                  @QueryParam("type") String type) {
        requireHrHuman();
        return hrLetterService.listAll(parseStatus(status), parseType(type));
    }

    @POST
    @Path("/{uuid}/approve")
    @RolesAllowed({"signing:write"})
    public HrLetterDTO approve(@PathParam("uuid") String uuid, ApproveHrLetterRequest request) {
        requireHrHuman();
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        log.infof("POST /hr-letters/%s/approve (template %s)", uuid, request.templateUuid());
        return hrLetterService.approveAndSend(uuid, requireActor(), request.templateUuid(), request.formValues());
    }

    @POST
    @Path("/{uuid}/dismiss")
    @RolesAllowed({"signing:write"})
    public void dismiss(@PathParam("uuid") String uuid, DismissHrLetterRequest request) {
        requireHrHuman();
        log.infof("POST /hr-letters/%s/dismiss", uuid);
        hrLetterService.dismiss(uuid, requireActor(), request != null ? request.reason() : null);
    }

    // ── Employee self-service ──────────────────────────────────────────────

    /** The acting employee's own letters (salary drafts never leak here). */
    @GET
    @Path("/mine")
    public List<HrLetterDTO> mine() {
        return hrLetterService.listOwn(requireActor());
    }

    /** Create or replace the acting employee's pending vacation-transfer request. */
    @POST
    @Path("/vacation-request")
    @RolesAllowed({"signing:write"})
    public HrLetterDTO requestVacationTransfer(VacationTransferRequestDTO request) {
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        return hrLetterService.createVacationTransferRequest(requireActor(), request.days(), request.fromYear());
    }

    /** Withdraw the acting employee's own pending vacation-transfer request. */
    @POST
    @Path("/{uuid}/withdraw")
    @RolesAllowed({"signing:write"})
    public void withdraw(@PathParam("uuid") String uuid) {
        hrLetterService.withdrawOwn(uuid, requireActor());
    }

    /** The acting employee's read-receipt on a delivered letter. */
    @POST
    @Path("/{uuid}/acknowledge")
    @RolesAllowed({"signing:write"})
    public HrLetterDTO acknowledge(@PathParam("uuid") String uuid) {
        return hrLetterService.acknowledge(uuid, requireActor());
    }

    // ── Gates ──────────────────────────────────────────────────────────────

    private String requireActor() {
        String actor = scopeGuard.actorOrNull();
        if (actor == null) {
            throw new ForbiddenException("An acting user is required for HR letters");
        }
        return actor;
    }

    private void requireHrHuman() {
        requireActor();
        if (!scopeGuard.actorHasUnbounded("salaries:read")) {
            throw new ForbiddenException("HR access is required for the letters console");
        }
    }

    private static HrLetterStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return HrLetterStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown status: " + raw);
        }
    }

    private static HrLetterType parseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return HrLetterType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown letter type: " + raw);
        }
    }
}
