package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.ai.AiIntakeGenerationService;
import dk.trustworks.intranet.recruitmentservice.ai.AiIntakeReactor;
import dk.trustworks.intranet.recruitmentservice.dto.AiResolveRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateAiStateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.CandidateAiReadService;
import dk.trustworks.intranet.recruitmentservice.services.CandidateAiReadService.IntakeGeneration;
import dk.trustworks.intranet.recruitmentservice.services.CandidateService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Candidate-scoped AI surfaces (P9, contract §6.2): the derived AI state,
 * the synchronous regenerate command and the suggestion resolve command.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Class-level {@code @RolesAllowed({"recruitment:read"})}; the two
 *       commands override with {@code recruitment:write}.</li>
 *   <li>The P8 endpoint ladder on every call: pipeline-flag enforce
 *       (404 + {@code admin:*} bypass) → actor from {@code X-Requested-By}
 *       → {@code requireVisibleCandidate} (invisible candidates answer
 *       404, never 403).</li>
 *   <li>Commands additionally require the recruiter tier (ADMIN/HR/CXO)
 *       or the hiring owner of the relevant position (403 otherwise), and
 *       regenerate requires the intake-or-brief toggle (same 404-style
 *       guard; NO bypass inside the toggles themselves — the {@code
 *       admin:*} bypass is the resource convention only).</li>
 *   <li>Regenerate is rate-limited to
 *       {@link CandidateAiReadService#DAILY_REGENERATION_LIMIT}/day (UTC)
 *       per candidate → 429 {@code RATE_LIMITED}.</li>
 * </ul>
 * Resolve semantics (contract §6.2): unknown/stale id → 409
 * {@code STALE_SUGGESTION}; already resolved → 200 no-op; accept applies
 * the stored value server-side via {@link CandidateService#update} patch
 * semantics (union for list fields; populated field → 409
 * {@code FIELD_ALREADY_SET}) — emitting {@code CANDIDATE_UPDATED} with
 * the USER as actor — then {@code AI_SUGGESTION_RESOLVED} in the same
 * transaction. Every command answers the fresh {@code ai/state} shape.
 * <p>
 * Class path is {@code /recruitment} (methods carry
 * {@code /candidates/...}) — NOT {@code /recruitment/candidates}: several
 * sibling resources rooted at {@code /recruitment} serve
 * {@code /candidates/*} sub-paths (profile reads, notes, triage queue),
 * and a class-level {@code /recruitment/candidates} would win JAX-RS
 * class matching by longest literal prefix and 404 every sibling
 * endpoint it does not itself declare.
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentCandidateAiResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentAiFeatureFlag aiFlags;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    CandidateAiReadService aiReadService;

    @Inject
    AiIntakeGenerationService generationService;

    @Inject
    CandidateService candidateService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    // ---- Read ----------------------------------------------------------------------

    /** The derived AI state: brief, unresolved suggestions, regenerate facts. */
    @GET
    @Path("/candidates/{uuid}/ai/state")
    public CandidateAiStateResponse state(@PathParam("uuid") UUID candidateUuid) {
        enforcePipelineFlag();
        UUID viewer = currentActor();
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, viewer);
        return aiReadService.state(viewer.toString(), candidate);
    }

    // ---- Regenerate ----------------------------------------------------------------

    /**
     * Run the intake/brief generation pipeline synchronously for the
     * candidate's latest open application ({@code origin=regenerate}).
     * The recovery path for flag-off gaps and CV re-uploads.
     * <p>
     * <b>Deliberately NOT {@code @Transactional}</b> (security review M1):
     * a method-level transaction would pin a pooled DB connection across
     * the OpenAI round-trip (up to the ~110 s read timeout), letting
     * concurrent regenerates exhaust the pool. The guard/rate-limit reads
     * here are read-only and non-transactional (same posture as the GET
     * {@code ai/state} endpoint — connections are released per statement);
     * {@link AiIntakeGenerationService#generateUntransacted} then gathers
     * inputs in a short completed transaction, performs the OpenAI call
     * with no transaction active, and appends the events in a fresh
     * transaction of its own. Wire behavior is unchanged.
     */
    @POST
    @Path("/candidates/{uuid}/ai/regenerate")
    @RolesAllowed({"recruitment:write"})
    @Consumes(MediaType.WILDCARD) // body-less POST — must not 415 on a missing Content-Type
    public CandidateAiStateResponse regenerate(@PathParam("uuid") UUID candidateUuid) {
        enforcePipelineFlag();
        enforceIntakeOrBriefFlag();
        UUID viewer = currentActor();
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, viewer);

        RecruitmentApplication anchor = AiIntakeReactor.latestOpenApplication(candidate.getUuid());
        if (anchor == null) {
            throw error(Response.Status.BAD_REQUEST, "NO_OPEN_APPLICATION",
                    "Needs an open application — attach the candidate to a position first");
        }
        RecruitmentPosition position = RecruitmentPosition.findById(anchor.getPositionUuid());
        requireAiActionTier(viewer, position);

        if (aiReadService.regenerationsToday(candidate.getUuid())
                >= CandidateAiReadService.DAILY_REGENERATION_LIMIT) {
            throw error(Response.Status.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "AI regeneration limit reached for today ("
                            + CandidateAiReadService.DAILY_REGENERATION_LIMIT + "/day)");
        }

        generationService.generateUntransacted(candidate, anchor,
                AiIntakeGenerationService.ORIGIN_REGENERATE, null, null);
        return aiReadService.state(viewer.toString(), candidate);
    }

    // ---- Resolve -------------------------------------------------------------------

    /** Accept or dismiss one suggestion from the latest generation. */
    @POST
    @Path("/candidates/{uuid}/ai/suggestions/resolve")
    @RolesAllowed({"recruitment:write"})
    @Transactional
    public CandidateAiStateResponse resolve(@PathParam("uuid") UUID candidateUuid,
                                            AiResolveRequest request) {
        enforcePipelineFlag();
        UUID viewer = currentActor();
        // Explicit input validation — bean validation is inert in this module.
        if (request == null || request.suggestionId() == null || request.suggestionId().isBlank()) {
            throw badRequest("suggestionId is required");
        }
        if (request.accepted() == null) {
            throw badRequest("accepted is required (true = apply, false = dismiss)");
        }
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, viewer);

        IntakeGeneration generation =
                aiReadService.latestVisibleIntakeGeneration(viewer.toString(), candidate.getUuid());
        Map<String, Object> suggestion = generation == null ? null
                : generation.suggestions().stream()
                        .filter(s -> request.suggestionId().equals(s.get("id")))
                        .findFirst()
                        .orElse(null);
        if (suggestion == null) {
            // Unknown id, or an id from a superseded generation — stale.
            throw error(Response.Status.CONFLICT, "STALE_SUGGESTION",
                    "The suggestion is not part of the latest generation");
        }
        RecruitmentPosition position = generation.event().getPositionUuid() == null ? null
                : RecruitmentPosition.findById(generation.event().getPositionUuid());
        requireAiActionTier(viewer, position);

        if (aiReadService.resolvedSuggestionIds(candidate.getUuid())
                .contains(request.suggestionId())) {
            // Double-click safe: already resolved ⇒ 200 no-op, no new events.
            return aiReadService.state(viewer.toString(), candidate);
        }

        String field = String.valueOf(suggestion.get("field"));
        if (Boolean.TRUE.equals(request.accepted())) {
            if (aiReadService.isFieldPopulated(candidate, field)) {
                throw error(Response.Status.CONFLICT, "FIELD_ALREADY_SET",
                        "The candidate field was set after the suggestion was generated");
            }
            // Server-side apply via the canonical update path (patch
            // semantics) — emits CANDIDATE_UPDATED with the USER as actor.
            candidateService.update(candidateUuid,
                    patchRequestFor(candidate, field, suggestion.get("value")), viewer);
        }
        appendResolvedEvent(generation.event(), candidate, viewer,
                request.suggestionId(), generation.generationId(), field, request.accepted());
        return aiReadService.state(viewer.toString(), candidate);
    }

    // ---- Resolve internals ---------------------------------------------------------

    /**
     * A {@link CandidateRequest} with ONLY the target field non-null —
     * {@code CandidateService.update}'s patch semantics leave every null
     * field untouched. List fields apply union(current, suggested).
     */
    private CandidateRequest patchRequestFor(RecruitmentCandidate candidate,
                                             String field, Object value) {
        CandidateEducationLevel education = null;
        CandidateExperienceLevel experience = null;
        List<String> specializations = null;
        List<String> languages = null;
        String currentEmployer = null;
        try {
            switch (field) {
                case AiIntakeGenerationService.FIELD_EDUCATION_LEVEL ->
                        education = CandidateEducationLevel.valueOf(String.valueOf(value));
                case AiIntakeGenerationService.FIELD_EXPERIENCE_LEVEL ->
                        experience = CandidateExperienceLevel.valueOf(String.valueOf(value));
                case AiIntakeGenerationService.FIELD_SPECIALIZATIONS ->
                        specializations = union(candidate.getSpecializations(), stringList(value));
                case AiIntakeGenerationService.FIELD_LANGUAGES ->
                        languages = union(candidate.getLanguages(), stringList(value));
                case AiIntakeGenerationService.FIELD_CURRENT_EMPLOYER ->
                        currentEmployer = String.valueOf(value);
                default -> throw new IllegalArgumentException("Unknown suggestion field: " + field);
            }
        } catch (IllegalArgumentException e) {
            // A stored value that can no longer be applied (renamed enum,
            // malformed event) behaves like a superseded suggestion.
            throw error(Response.Status.CONFLICT, "STALE_SUGGESTION",
                    "The stored suggestion value can no longer be applied");
        }
        return new CandidateRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                education, null, experience, specializations, null, null,
                languages, currentEmployer);
    }

    /** AI bookkeeping event — copies the generation's subjects and visibility. */
    private void appendResolvedEvent(RecruitmentEvent generationEvent, RecruitmentCandidate candidate,
                                     UUID actor, String suggestionId, String generationId,
                                     String field, boolean accepted) {
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.AI_SUGGESTION_RESOLVED)
                .candidate(candidate.getUuid())
                .application(generationEvent.getApplicationUuid())
                .position(generationEvent.getPositionUuid())
                .actorUser(actor.toString())
                .visibility(generationEvent.getVisibility())
                .payload("suggestion_id", suggestionId)
                .payload("generation_id", generationId)
                .payload("field", field)
                .payload("accepted", accepted);
        eventRecorder.record(event);
    }

    private static List<String> union(List<String> current, List<String> suggested) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (current != null) {
            merged.addAll(current);
        }
        merged.addAll(suggested);
        return new ArrayList<>(merged);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected a string list value");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Empty list value");
        }
        return out;
    }

    // ---- Guards --------------------------------------------------------------------

    /**
     * The AI command tier (contract §6.2): recruiter tier (ADMIN/HR/CXO)
     * or the hiring owner of the relevant position. 403 — the caller can
     * see the candidate, so existence is no secret.
     */
    private void requireAiActionTier(UUID actor, RecruitmentPosition position) {
        if (visibility.isRecruiterTier(actor.toString())) {
            return;
        }
        if (position != null && actor.toString().equals(position.getHiringOwnerUuid())) {
            return;
        }
        throw new WebApplicationException(
                "AI actions are reserved for the recruiter tier or the position's hiring owner",
                Response.Status.FORBIDDEN);
    }

    /** Standard sibling-resource convention: 404 + admin:* bypass. */
    private void enforcePipelineFlag() {
        if (featureFlag.isPipelineEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    /**
     * Regenerate needs at least one of the two intake-side toggles. Same
     * 404-style guard (+ the resource-level {@code admin:*} bypass — the
     * toggles themselves have no bypass; reactors read them literally).
     */
    private void enforceIntakeOrBriefFlag() {
        if (aiFlags.isIntakeEnabled() || aiFlags.isBriefEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    /** The P8 helper verbatim: invisible candidates answer 404, never 403. */
    private RecruitmentCandidate requireVisibleCandidate(UUID candidateUuid, UUID viewer) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null
                || !visibility.canReadCandidateProfile(viewer.toString(), candidate)) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return candidate;
    }

    /** Resolve the acting user from {@code X-Requested-By}; 400 when absent. */
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

    /** Coded error entity: {@code {"error": CODE, "message": ...}}. */
    private static WebApplicationException error(Response.Status status, String code, String message) {
        return new WebApplicationException(Response.status(status)
                .entity(Map.of("error", code, "message", Objects.requireNonNullElse(message, code)))
                .build());
    }
}
