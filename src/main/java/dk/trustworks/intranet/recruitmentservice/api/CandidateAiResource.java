package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.AiArtifactResponse;
import dk.trustworks.intranet.recruitmentservice.application.AiArtifactService;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateCv;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.recruitmentservice.ports.CvToolPort;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for AI artifacts attached to a {@link Candidate} (spec §6.3).
 *
 * <p>Currently exposes a single trigger:
 * <ul>
 *   <li>{@code POST /api/recruitment/candidates/{uuid}/ai/summary} — enqueue a
 *       {@link AiArtifactKind#CANDIDATE_SUMMARY} generation. Returns 202 Accepted with
 *       an {@link AiArtifactResponse} DTO (state is GENERATING until the worker fulfils it).</li>
 * </ul>
 *
 * <p>Spec §6.3 deliberately uses the {@code recruitment:read} scope here because the summary
 * is informational — accepting it does not mutate the Candidate aggregate (no apply-handler
 * is registered for {@link AiArtifactKind#CANDIDATE_SUMMARY} in
 * {@link AiArtifactService#runApplyHandler}).
 *
 * <p>Idempotency is handled by {@link AiArtifactService#requestArtifact} via the input
 * digest: composing the same {@code (subject_kind, subject_uuid, kind, input_digest)}
 * tuple returns the existing row instead of creating a duplicate, so callers can safely
 * retry without producing fan-out outbox entries.
 *
 * <p>Access control mirrors {@link CandidateCvResource}: the caller must hold
 * {@code recruitment:read} <em>and</em> pass the record-level
 * {@link RecruitmentRecordAccessService#canSeeCandidate(Candidate, String)} check. We
 * deliberately collapse "candidate not found" and "candidate not visible" to 404 to avoid
 * leaking the existence of candidates outside the caller's scope (see commit {@code c388184}).
 *
 * <p>Prompt inputs (per spec §6.3): the candidate's profile fields, the URL of the latest
 * accepted CV (so the worker can pull extracted text), and a comparables roster of existing
 * Trustworks consultants in the same practice via {@link CvToolPort}.
 */
@Path("/api/recruitment/candidates/{uuid}/ai")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CandidateAiResource {

    private static final int COMPARABLES_LIMIT = 5;

    @Inject AiArtifactService artifacts;
    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject CvToolPort cvTool;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/summary")
    @RolesAllowed({"recruitment:read"})
    public Response triggerSummary(@PathParam("uuid") String candidateUuid) {
        String actor = header.getUserUuid();
        Candidate candidate = Candidate.findById(candidateUuid);
        if (candidate == null || !recordAccess.canSeeCandidate(candidate, actor)) {
            throw new NotFoundException("Candidate " + candidateUuid);
        }

        // Pull the latest current CV so the worker can join its extracted text into the prompt.
        CandidateCv currentCv = CandidateCv.find(
                "candidateUuid = ?1 and isCurrent = true", candidateUuid).firstResult();

        // Pull comparables from CV Tool keyed on the candidate's desired practice. When the
        // candidate has no practice preference yet we send an empty list — the worker treats
        // it as "no comparables available" rather than failing.
        List<CvToolPort.EmployeeCvSummary> comparables = candidate.desiredPractice == null
                ? List.of()
                : cvTool.findByPractice(candidate.desiredPractice.name(), COMPARABLES_LIMIT);

        Map<String, Object> candidateMap = new LinkedHashMap<>();
        candidateMap.put("firstName", candidate.firstName);
        candidateMap.put("lastName", candidate.lastName);
        candidateMap.put("desiredPractice", candidate.desiredPractice == null ? null : candidate.desiredPractice.name());
        candidateMap.put("desiredCareerLevelUuid", candidate.desiredCareerLevelUuid);
        candidateMap.put("currentCompany", candidate.currentCompany);
        candidateMap.put("tags", candidate.tags);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("candidate", candidateMap);
        inputs.put("currentCvUrl", currentCv == null ? null : currentCv.fileUrl);
        inputs.put("comparablesFromCvTool", comparables);

        AiArtifact artifact = artifacts.requestArtifact(
                AiSubjectKind.CANDIDATE, candidateUuid, AiArtifactKind.CANDIDATE_SUMMARY, inputs, actor);
        return Response.status(Response.Status.ACCEPTED).entity(AiArtifactResponse.from(artifact)).build();
    }
}
