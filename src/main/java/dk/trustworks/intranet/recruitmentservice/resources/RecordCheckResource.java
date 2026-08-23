package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentRecordCheck;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecordCheckOutcome;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecordCheckService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * ISAE 3000 record-check sampling API (V481): per-candidate draw status,
 * outcome recording, and the sample-rate setting. Explicit validation —
 * {@code @Valid} is inert in this repo. The BFF gates users (HR records
 * outcomes and edits the rate); this class carries the client scopes.
 */
@JBossLog
@RequestScoped
@Path("/recruitment/record-checks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RecordCheckResource {

    @Inject
    RecordCheckService recordCheckService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    public record OutcomeRequest(String outcome) {
    }

    public record RateSettings(int sampleRatePercent) {
    }

    /**
     * Minimum dossier-card projection. The source application and actor/audit
     * columns are intentionally absent: the UI does not consume them, and an
     * application UUID can reveal a hidden partner route for a mixed-scope
     * candidate.
     */
    public record RecordCheckStatusResponse(
            LocalDateTime drawnAt,
            int rateApplied,
            boolean selected,
            RecordCheckOutcome outcome,
            LocalDateTime verifiedAt,
            boolean viewerCanRecordOutcome) {
    }

    /** The candidate's draw row, or 404 when the candidate never reached OFFER. */
    @GET
    @Path("/{candidateUuid}")
    @RolesAllowed({"recruitment:read"})
    public Response status(@PathParam("candidateUuid") String candidateUuid) {
        UUID viewer = actor();
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null
                || !visibility.canReadDossier(viewer.toString(), candidate)) {
            // Uniform with a missing draw/candidate: this endpoint is mounted
            // inside Offer & Contract and must not become a dossier-existence
            // or cross-candidate oracle for a caller who knows a UUID.
            throw new NotFoundException();
        }
        boolean viewerCanRecordOutcome = isHrOrAdmin(viewer);
        return recordCheckService.statusFor(candidateUuid)
                .map(check -> Response.ok(toStatusResponse(
                        check, viewerCanRecordOutcome)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"NOT_FOUND\"}")
                        .build());
    }

    /** HR records the verification outcome (outcome only — never the attest). */
    @POST
    @Path("/{candidateUuid}/outcome")
    @RolesAllowed({"recruitment:write"})
    public RecordCheckStatusResponse recordOutcome(
            @PathParam("candidateUuid") String candidateUuid,
            OutcomeRequest request) {
        UUID actor = requireHrOrAdmin();
        RecordCheckOutcome outcome = parseOutcome(request == null ? null : request.outcome());
        return toStatusResponse(
                recordCheckService.recordOutcome(candidateUuid, outcome, actor), true);
    }

    @GET
    @Path("/settings")
    @RolesAllowed({"recruitment:read"})
    public RateSettings settings() {
        requireHrOrAdmin();
        return new RateSettings(recordCheckService.sampleRatePercent());
    }

    @PUT
    @Path("/settings")
    @RolesAllowed({"recruitment:write"})
    public RateSettings updateSettings(RateSettings request) {
        UUID actor = requireHrOrAdmin();
        recordCheckService.updateSampleRate(request.sampleRatePercent(), actor);
        return new RateSettings(recordCheckService.sampleRatePercent());
    }

    // ---- Helpers ---------------------------------------------------------------

    /**
     * Resolve the acting user from {@code X-Requested-By} (set by
     * {@code HeaderInterceptor}). 400 when absent — draw audit and
     * outcome verification are per-user records.
     */
    private UUID actor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw badRequest("MISSING_ACTOR");
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw badRequest("MISSING_ACTOR");
        }
    }

    /** Compliance outcomes and the global draw rate belong to HR/admin only. */
    private UUID requireHrOrAdmin() {
        UUID actor = actor();
        if (!isHrOrAdmin(actor)) {
            throw new WebApplicationException(
                    "Record-check outcomes and settings are reserved for HR or administrators",
                    Response.Status.FORBIDDEN);
        }
        return actor;
    }

    private boolean isHrOrAdmin(UUID actor) {
        Set<String> roles = visibility.rolesOf(actor.toString());
        return roles.contains("ADMIN") || roles.contains("HR");
    }

    private static RecordCheckStatusResponse toStatusResponse(
            RecruitmentRecordCheck check,
            boolean viewerCanRecordOutcome) {
        return new RecordCheckStatusResponse(
                check.getDrawnAt(),
                check.getRateApplied(),
                check.isSelected(),
                check.getOutcome(),
                check.getVerifiedAt(),
                viewerCanRecordOutcome);
    }

    private static RecordCheckOutcome parseOutcome(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badRequest("INVALID_OUTCOME");
        }
        try {
            return RecordCheckOutcome.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("INVALID_OUTCOME");
        }
    }

    private static WebApplicationException badRequest(String code) {
        return new WebApplicationException(Response
                .status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + code + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
