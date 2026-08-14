package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.PublicSchedulingResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingCandidateService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

/**
 * The Method B candidate option page's endpoints (plan §11.1) — the ONLY
 * anonymous scheduling surface, mirroring {@link PublicApplyResource}:
 * {@code @PermitAll}, throttled by {@code PublicApplyRateLimitFilter}
 * (the {@code /public/scheduling} prefix is on its path list), and
 * uniformly failing: bad token shape, unknown token, expired or closed
 * batch, request beyond the choosing states, flag off — every one of them
 * answers the byte-identical {@code 404 {"error":"NOT_FOUND"}}. The
 * 256-bit token is the capability; there is nothing else to authorize.
 *
 * <h3>Deliberate non-404s</h3>
 * <ul>
 *   <li>Selecting an option that just died while others remain:
 *       {@code 409 {"error":"OPTION_INVALID"}} — the page re-fetches and
 *       asks the candidate to choose again (spec §16.2).</li>
 *   <li>A repeat POST of a committed action answers the committed
 *       outcome (idempotent-save idiom) — a double-click never strands
 *       the candidate.</li>
 * </ul>
 */
@JBossLog
@RequestScoped
@Path("/public/scheduling")
@Produces(MediaType.APPLICATION_JSON)
public class PublicSchedulingResource {

    private static final String NOT_FOUND_BODY = "{\"error\":\"NOT_FOUND\"}";
    private static final String RECEIVED_BODY = "{\"status\":\"RECEIVED\"}";

    private static final int NOTE_MAX_LENGTH = 500;

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    RecruitmentSchedulingCandidateService candidateService;

    /** The option page's data. */
    @GET
    @PermitAll
    @Path("/{token}")
    public PublicSchedulingResponse view(@PathParam("token") String token) {
        requireFlag();
        PublicSchedulingResponse view = candidateService.publicView(token);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    /** Body of {@code POST /{token}/select}. */
    public record SelectRequest(String optionId) {
    }

    /** Pick one option — first committed selection wins. */
    @POST
    @PermitAll
    @Path("/{token}/select")
    public PublicSchedulingResponse select(@PathParam("token") String token,
                                           SelectRequest body) {
        requireFlag();
        if (body == null || body.optionId() == null || body.optionId().isBlank()) {
            throw badRequest("MISSING_OPTION");
        }
        RecruitmentSchedulingCandidateService.SelectOutcome outcome =
                candidateService.select(token, body.optionId().trim());
        return switch (outcome) {
            case RecruitmentSchedulingCandidateService.SelectOutcome.NotFound ignored ->
                    throw notFound();
            case RecruitmentSchedulingCandidateService.SelectOutcome.InvalidOption ignored ->
                    throw new WebApplicationException(Response
                            .status(Response.Status.CONFLICT)
                            .entity("{\"error\":\"OPTION_INVALID\"}")
                            .type(MediaType.APPLICATION_JSON)
                            .build());
            case RecruitmentSchedulingCandidateService.SelectOutcome.Committed committed ->
                    committed.view();
        };
    }

    /** Body of {@code POST /{token}/none-work}. */
    public record NoneWorkRequest(String note) {
    }

    /** None of the options work — escalate to the recruiter. */
    @POST
    @PermitAll
    @Path("/{token}/none-work")
    public Response noneWork(@PathParam("token") String token,
                             NoneWorkRequest body) {
        requireFlag();
        String note = body == null ? null : trimToNull(body.note());
        if (note != null && note.length() > NOTE_MAX_LENGTH) {
            throw badRequest("NOTE_TOO_LONG");
        }
        if (!candidateService.noneWork(token, note)) {
            throw notFound();
        }
        return Response.ok(RECEIVED_BODY).type(MediaType.APPLICATION_JSON).build();
    }

    // ---- Plumbing ----------------------------------------------------------

    /** Flag off ⇒ the whole surface is absent — uniform 404, no bypass. */
    private void requireFlag() {
        if (!methodBFlag.isMethodBEnabled()) {
            throw notFound();
        }
    }

    private static WebApplicationException notFound() {
        return new WebApplicationException(Response
                .status(Response.Status.NOT_FOUND)
                .entity(NOT_FOUND_BODY)
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /** Code-only 400 — submitted values are never echoed. */
    private static WebApplicationException badRequest(String code) {
        return new WebApplicationException(Response
                .status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + code + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
