package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.AiArtifactRegenerateRequest;
import dk.trustworks.intranet.recruitmentservice.api.dto.AiArtifactResponse;
import dk.trustworks.intranet.recruitmentservice.api.dto.AiArtifactReviewRequest;
import dk.trustworks.intranet.recruitmentservice.application.AiArtifactService;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * REST endpoints for AI artifacts (spec §6.9).
 *
 * <p>Slice 2 endpoints:
 * <ul>
 *   <li>{@code GET /api/recruitment/ai-artifacts/{uuid}} — returns the current
 *       state of an artifact for the review UI. Scope: {@code recruitment:read}.</li>
 *   <li>{@code POST /api/recruitment/ai-artifacts/{uuid}/review} — reviewer
 *       disposition (accept / edit-with-override / discard). Scope:
 *       {@code recruitment:write}. The body's {@code accepted}/{@code overrideJson}
 *       pair is dispatched to {@link AiArtifactService#accept},
 *       {@link AiArtifactService#editAndOverride}, or
 *       {@link AiArtifactService#discard}. The service refuses with 409 if the
 *       artifact is not in GENERATED state.</li>
 *   <li>{@code POST /api/recruitment/ai-artifacts/{uuid}/regenerate} — force a
 *       fresh AI attempt. Scope: {@code recruitment:write}. The previous
 *       artifact is left untouched (audit trail); a brand-new artifact is
 *       returned. The optional {@code reason} is folded into the inputs so
 *       the digest differs from the prior attempt.</li>
 * </ul>
 *
 * <p>Access control is <strong>subject-derived</strong>: the artifact's
 * {@code subject_kind}/{@code subject_uuid} pair points at the underlying
 * Candidate or OpenRole, and we apply the same record-level visibility check
 * already enforced on those resources <em>before</em> any state mutation.
 * Following the convention established in {@link OpenRoleAiResource} and
 * {@link CandidateAiResource}, "artifact not found" and "artifact not visible"
 * both collapse to 404 to avoid leaking the existence of subjects outside the
 * caller's scope.
 *
 * <p>Slice 3a adds a record-level check for {@code INTERVIEW} subjects
 * (INTERVIEW_KIT and SCORECARD_ROUNDUP artifacts) using the same
 * {@code canSeeInterview} predicate the {@code /interviews} resource enforces.
 * Subject kinds APPLICATION/OFFER are still not in scope and fall through to
 * the open-by-scope branch; later slices will add concrete record-level checks
 * once those aggregates have visibility rules.
 */
@Path("/api/recruitment/ai-artifacts")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AiArtifactResource {

    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject RequestHeaderHolder header;
    @Inject AiArtifactService artifacts;

    @GET
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:read"})
    public AiArtifactResponse get(@PathParam("uuid") String uuid) {
        AiArtifact artifact = AiArtifact.findById(uuid);
        if (artifact == null) {
            throw new NotFoundException("AiArtifact " + uuid);
        }

        String actor = header.getUserUuid();
        enforceSubjectVisibility(artifact, actor, uuid);

        return AiArtifactResponse.from(artifact);
    }

    @POST
    @Path("/{uuid}/review")
    @RolesAllowed({"recruitment:write"})
    public AiArtifactResponse review(@PathParam("uuid") String uuid, AiArtifactReviewRequest req) {
        AiArtifact artifact = AiArtifact.findById(uuid);
        if (artifact == null) {
            throw new NotFoundException("AiArtifact " + uuid);
        }

        String actor = header.getUserUuid();
        // Record-level access MUST be enforced before any state mutation —
        // scope alone is not enough on a write endpoint that touches a
        // candidate/role the caller cannot see.
        enforceSubjectVisibility(artifact, actor, uuid);

        if (req.accepted()) {
            artifacts.accept(uuid, actor);
        } else if (req.overrideJson() != null && !req.overrideJson().isBlank()) {
            artifacts.editAndOverride(uuid, actor, req.overrideJson());
        } else {
            artifacts.discard(uuid, actor);
        }
        return AiArtifactResponse.from(AiArtifact.findById(uuid));
    }

    @POST
    @Path("/{uuid}/regenerate")
    @RolesAllowed({"recruitment:write"})
    public AiArtifactResponse regenerate(@PathParam("uuid") String uuid, AiArtifactRegenerateRequest req) {
        AiArtifact prev = AiArtifact.findById(uuid);
        if (prev == null) {
            throw new NotFoundException("AiArtifact " + uuid);
        }

        String actor = header.getUserUuid();
        // Same record-level guard as GET/review: the new artifact will share the
        // previous one's subject, so the caller must be able to see that subject
        // before we kick off a fresh attempt.
        enforceSubjectVisibility(prev, actor, uuid);

        AiArtifact next = artifacts.regenerate(uuid, actor, req == null ? null : req.reason());
        return AiArtifactResponse.from(next);
    }

    /**
     * Apply the same record-level visibility rule the underlying subject's
     * resource uses. Throws 404 (not 403) for invisible subjects to avoid
     * leaking existence — same convention as the GET endpoint.
     */
    private void enforceSubjectVisibility(AiArtifact artifact, String actor, String artifactUuid) {
        if (artifact.subjectKind == AiSubjectKind.CANDIDATE) {
            Candidate candidate = Candidate.findById(artifact.subjectUuid);
            if (candidate == null || !recordAccess.canSeeCandidate(candidate, actor)) {
                throw new NotFoundException("AiArtifact " + artifactUuid);
            }
        } else if (artifact.subjectKind == AiSubjectKind.ROLE) {
            OpenRole role = OpenRole.findById(artifact.subjectUuid);
            if (role == null || !recordAccess.canSeeOpenRole(role, actor)) {
                throw new NotFoundException("AiArtifact " + artifactUuid);
            }
        } else if (artifact.subjectKind == AiSubjectKind.INTERVIEW) {
            // Slice 3a: INTERVIEW_KIT and SCORECARD_ROUNDUP artifacts are subject-bound
            // to a specific interview. Use the same record-level guard the
            // /interviews resource enforces — recruiters / participants / privileged
            // viewers only. 404 (not 403) preserves the existence-leak convention.
            Interview iv = Interview.findById(artifact.subjectUuid);
            if (iv == null || !recordAccess.canSeeInterview(iv, actor)) {
                throw new NotFoundException("AiArtifact " + artifactUuid);
            }
        }
        // APPLICATION/OFFER not used yet — pass through on scope alone.
    }
}
