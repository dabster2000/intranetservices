package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateBriefResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateConsentsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateDocumentsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateTimelineResponse;
import dk.trustworks.intranet.recruitmentservice.dto.FactsLedgerResponse;
import dk.trustworks.intranet.recruitmentservice.dto.FormAnswersResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.CandidateBriefService;
import dk.trustworks.intranet.recruitmentservice.services.CandidateProfileReadService;
import dk.trustworks.intranet.recruitmentservice.services.CandidateProfileReadService.DocumentDownload;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactLedgerService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentTimelineService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;

/**
 * The P8 candidate-profile read surfaces (spec §6.1
 * {@code /recruitment/candidates/[uuid]}, plan §P8): timeline, form
 * answers (candidate-scoped leg), documents (+ download) and consents.
 * Read-only by design — every mutation stays on its command resource.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Class-level {@code @RolesAllowed({"recruitment:read"})} — client
 *       scope; the BFF holds {@code admin:*}.</li>
 *   <li>Per-user profile access via
 *       {@link RecruitmentVisibility#canReadCandidateProfile} on EVERY
 *       endpoint: ADMIN always; the profile-read tier
 *       (HR/RECRUITMENT/TEAMLEAD) minus partner-track-only candidates;
 *       otherwise ownership or current leadership (hiring owner, team lead,
 *       practice lead) of a non-partner position the candidate applied to;
 *       hired files narrowed to ADMIN+HR+RECRUITMENT+DPO. Interviewers and
 *       circle-only viewers get the restricted brief instead
 *       ({@code /brief}), never this profile. Invisible candidates answer
 *       404, never 403 — existence must not leak.</li>
 *   <li>Event-level filtering (CIRCLE events, private notes, salary pii)
 *       lives in {@link RecruitmentTimelineService}, applied AFTER profile
 *       access.</li>
 *   <li>Offer/onboarding documents require the separate, candidate-scoped
 *       {@link RecruitmentVisibility#canReadDossier} capability. Profile
 *       readers without it see only CV, cover-letter and ordinary files.</li>
 *   <li>Feature flag {@code recruitment.pipeline.enabled}: off + non-admin
 *       caller → 404 (sibling-resource convention).</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentCandidateProfileResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    /** Timeline page-size default and explicit hard cap (P8 contract). */
    static final int DEFAULT_TIMELINE_LIMIT = 100;
    static final int MAX_TIMELINE_LIMIT = 200;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentTimelineService timelineService;

    @Inject
    CandidateProfileReadService profileReadService;

    @Inject
    CandidateBriefService briefService;

    @Inject
    RecruitmentFactLedgerService factLedgerService;

    // ---- Restricted brief ----------------------------------------------------------

    /**
     * The <b>restricted candidate brief</b> (go-live decisions D10–D12) —
     * the only recruitment surface reachable without a recruitment role.
     * Granted to an assigned interviewer or a member of one of the
     * candidate's positions' circles, while the candidate is ACTIVE.
     * <p>
     * Deliberately narrower than every other endpoint on this resource: no
     * stage, no status, no timeline, no comp data, no notes, no other
     * people's scorecards — see {@link CandidateBriefResponse}. A viewer
     * who can read the full profile may also call this (it is additive,
     * never a downgrade), but the frontend routes them to the profile.
     * <p>
     * No access → 404, never 403: existence must not leak, exactly as for
     * the full profile.
     */
    @GET
    @Path("/candidates/{uuid}/brief")
    @RolesAllowed({"recruitment:interview"})
    public CandidateBriefResponse brief(@PathParam("uuid") UUID candidateUuid) {
        enforcePipelineFlag();
        UUID viewer = currentActor();
        RecruitmentCandidate candidate = requireBriefableCandidate(candidateUuid, viewer);
        return briefService.brief(viewer.toString(), candidate);
    }

    // ---- Timeline ----------------------------------------------------------------

    /**
     * The candidate's event timeline, newest first ({@code seq} DESC).
     * {@code limit} defaults to 100 and is explicitly capped at 200;
     * {@code beforeSeq} is an exclusive cursor (pass the smallest seq of
     * the previous page).
     */
    @GET
    @Path("/candidates/{uuid}/events")
    public CandidateTimelineResponse events(@PathParam("uuid") UUID candidateUuid,
                                            @QueryParam("limit") Integer limit,
                                            @QueryParam("beforeSeq") Long beforeSeq) {
        enforcePipelineFlag();
        UUID viewer = currentActor();
        int effectiveLimit = normalizeLimit(limit);
        if (beforeSeq != null && beforeSeq <= 0) {
            throw badRequest("beforeSeq must be a positive event sequence number");
        }
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, viewer);
        return timelineService.timeline(viewer.toString(), candidate, effectiveLimit, beforeSeq);
    }

    // ---- Fact ledger (Interview Room spec §4.3/§6.2) --------------------------------

    /**
     * The hiring-fact ledger: every vocabulary field with its derived state
     * (UNKNOWN/ASKED/STATED/CONFIRMED/STALE), the newest value where the
     * viewer may read it, and the append-only drift history. Candidate
     * boundary (profile visibility) is the per-person gate; compensation
     * VALUES additionally require the comp tier — non-comp viewers get
     * {@code redacted=true} entries, never the text. Gated by
     * {@code recruitment.facts.enabled} (slice 0), 404 when off for
     * non-admin callers, same convention as the pipeline flag.
     */
    @GET
    @Path("/candidates/{uuid}/facts")
    public FactsLedgerResponse facts(@PathParam("uuid") UUID candidateUuid) {
        enforcePipelineFlag();
        enforceFactsFlag();
        UUID viewer = currentActor();
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, viewer);
        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("candidateUuid", candidate.getUuid());
        List<RecruitmentPosition> positions = applications.isEmpty() ? List.of()
                : RecruitmentPosition.list("uuid in ?1", applications.stream()
                        .map(RecruitmentApplication::getPositionUuid).distinct().toList());
        boolean compTier = visibility.isCompTierFor(viewer.toString(), positions);
        return factLedgerService.ledger(candidate, compTier);
    }

    // ---- Form answers (candidate-scoped leg) ---------------------------------------

    /**
     * The candidate-scoped form answers — the V437 leg for unsolicited
     * applicants who have answers but no application yet (findings §P5).
     * Application-scoped answers live on
     * {@code GET /recruitment/applications/{uuid}/answers}.
     */
    @GET
    @Path("/candidates/{uuid}/answers")
    public FormAnswersResponse answers(@PathParam("uuid") UUID candidateUuid) {
        enforcePipelineFlag();
        UUID viewer = currentActor();
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, viewer);
        return profileReadService.answersForCandidate(candidate.getUuid());
    }

    // ---- Documents -----------------------------------------------------------------

    /**
     * The candidate's stored files, enriched from their
     * {@code DOCUMENT_UPLOADED} events (kind, origin, duplicate reason).
     */
    @GET
    @Path("/candidates/{uuid}/documents")
    public CandidateDocumentsResponse documents(@PathParam("uuid") UUID candidateUuid) {
        enforcePipelineFlag();
        UUID viewer = currentActor();
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, viewer);
        return profileReadService.documents(
                candidate.getUuid(),
                visibility.canReadDossier(viewer.toString(), candidate));
    }

    /**
     * Stream one document's bytes. Two authz shapes meet here:
     * <ul>
     *   <li>full-profile viewers get ordinary files; offer/onboarding kinds
     *       additionally require the candidate-scoped dossier capability;</li>
     *   <li>restricted viewers (assigned interviewer / circle member) get
     *       only the files that appear on their brief — CV, cover letter
     *       and unclassified uploads. A contract draft, signed document,
     *       appendix or identity document answers 404 even when its uuid
     *       is known, so guessing a uuid buys nothing.</li>
     * </ul>
     * The IDOR guard (file's {@code relateduuid} must match the candidate
     * in the URL) still runs inside the read service.
     */
    @GET
    @Path("/candidates/{uuid}/documents/{fileUuid}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadDocument(@PathParam("uuid") UUID candidateUuid,
                                     @PathParam("fileUuid") String fileUuid) {
        enforcePipelineFlag();
        UUID viewer = currentActor();
        if (fileUuid == null || fileUuid.isBlank()) {
            throw badRequest("fileUuid is required");
        }
        RecruitmentCandidate candidate = requireBriefableCandidate(candidateUuid, viewer);
        if (!visibility.canReadCandidateProfile(viewer.toString(), candidate)
                && !briefService.downloadableFileUuids(candidate.getUuid()).contains(fileUuid)) {
            throw new NotFoundException("Document not found: " + fileUuid);
        }
        DocumentDownload download = profileReadService.download(
                candidate.getUuid(),
                fileUuid,
                visibility.canReadDossier(viewer.toString(), candidate));
        return Response.ok(download.bytes(), download.contentType())
                .header("Content-Disposition",
                        "attachment; filename=\"" + headerSafe(download.filename()) + "\"")
                .build();
    }

    // ---- Consents ------------------------------------------------------------------

    /** Read-only consent rows for the GDPR tab. */
    @GET
    @Path("/candidates/{uuid}/consents")
    public CandidateConsentsResponse consents(@PathParam("uuid") UUID candidateUuid) {
        enforcePipelineFlag();
        UUID viewer = currentActor();
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, viewer);
        return profileReadService.consents(candidate.getUuid());
    }

    // ---- Helpers -------------------------------------------------------------------

    /**
     * Resolve the candidate and apply the P8 profile rule: an
     * existing-but-invisible candidate (partner-track-only outside the
     * viewer's circles, hired file outside the narrowed tier, no
     * involvement) answers the same 404 as a nonexistent one.
     */
    private RecruitmentCandidate requireVisibleCandidate(UUID candidateUuid, UUID viewer) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null
                || !visibility.canReadCandidateProfile(viewer.toString(), candidate)) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return candidate;
    }

    /**
     * The looser gate of the two: the viewer may read the full profile
     * <em>or</em> holds the restricted grant (assigned interviewer / circle
     * member on an ACTIVE candidate). Used by the brief and by the document
     * download — everything else on this resource stays profile-only.
     * Same 404-on-invisible convention.
     */
    private RecruitmentCandidate requireBriefableCandidate(UUID candidateUuid, UUID viewer) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null
                || (!visibility.canReadCandidateProfile(viewer.toString(), candidate)
                    && !visibility.canReadRestrictedCandidateView(viewer.toString(), candidate))) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return candidate;
    }

    /** Default 100; explicit hard cap 200; non-positive values answer 400. */
    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_TIMELINE_LIMIT;
        }
        if (limit <= 0) {
            throw badRequest("limit must be a positive number (max " + MAX_TIMELINE_LIMIT + ")");
        }
        return Math.min(limit, MAX_TIMELINE_LIMIT);
    }

    /** Strip characters that could break out of the Content-Disposition header. */
    private static String headerSafe(String filename) {
        return filename.replaceAll("[\"\\r\\n\\\\]", "_");
    }

    /**
     * Block the request when {@code recruitment.pipeline.enabled} is off,
     * unless the caller holds {@code admin:*}. 404 (not 503) to avoid
     * leaking the feature's existence — sibling-resource convention.
     */
    private void enforcePipelineFlag() {
        if (featureFlag.isPipelineEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    /** Slice-0 gate for the fact ledger — same 404 + admin-bypass convention. */
    private void enforceFactsFlag() {
        if (featureFlag.isFactsEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    /**
     * Resolve the acting user from {@code X-Requested-By} (set by
     * {@code HeaderInterceptor}). 400 when absent — every rule on this
     * resource is per-user.
     */
    private UUID currentActor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw badRequest("X-Requested-By header is required");
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw badRequest("X-Requested-By is not a valid UUID");
        }
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }
}
