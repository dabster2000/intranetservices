package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.AiArtifactResponse;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * REST endpoints for AI artifacts (spec §6.9).
 *
 * <p>Slice 2 ships only the read endpoint:
 * <ul>
 *   <li>{@code GET /api/recruitment/ai-artifacts/{uuid}} — returns the current
 *       state of an artifact for the review UI. Scope: {@code recruitment:read}
 *       (the resource itself is informational; mutating endpoints come in
 *       Tasks 34/35).</li>
 * </ul>
 *
 * <p>Access control is <strong>subject-derived</strong>: the artifact's
 * {@code subject_kind}/{@code subject_uuid} pair points at the underlying
 * Candidate or OpenRole, and we apply the same record-level visibility check
 * already enforced on those resources. Following the convention established
 * in {@link OpenRoleAiResource} and {@link CandidateAiResource}, "artifact not
 * found" and "artifact not visible" both collapse to 404 to avoid leaking the
 * existence of subjects outside the caller's scope.
 *
 * <p>Subject kinds APPLICATION/INTERVIEW/OFFER are not in scope for Slice 2;
 * artifacts of those kinds will fall through to the open-by-scope branch and
 * be returned to any holder of {@code recruitment:read} for now. Slice 3 will
 * add concrete record-level checks once those aggregates have visibility rules.
 */
@Path("/api/recruitment/ai-artifacts")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AiArtifactResource {

    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject RequestHeaderHolder header;

    @GET
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:read"})
    public AiArtifactResponse get(@PathParam("uuid") String uuid) {
        AiArtifact artifact = AiArtifact.findById(uuid);
        if (artifact == null) {
            throw new NotFoundException("AiArtifact " + uuid);
        }

        String actor = header.getUserUuid();
        if (artifact.subjectKind == AiSubjectKind.CANDIDATE) {
            Candidate candidate = Candidate.findById(artifact.subjectUuid);
            if (candidate == null || !recordAccess.canSeeCandidate(candidate, actor)) {
                throw new NotFoundException("AiArtifact " + uuid);
            }
        } else if (artifact.subjectKind == AiSubjectKind.ROLE) {
            OpenRole role = OpenRole.findById(artifact.subjectUuid);
            if (role == null || !recordAccess.canSeeOpenRole(role, actor)) {
                throw new NotFoundException("AiArtifact " + uuid);
            }
        }
        // APPLICATION/INTERVIEW/OFFER not used in Slice 2 — pass through on scope alone.

        return AiArtifactResponse.from(artifact);
    }
}
