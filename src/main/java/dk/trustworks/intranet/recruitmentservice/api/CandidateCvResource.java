package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.CandidateCvResponse;
import dk.trustworks.intranet.recruitmentservice.application.CvUploadService;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateCv;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;

/**
 * REST endpoints for candidate CV uploads (spec §6.3).
 *
 * <ul>
 *   <li>{@code POST /api/recruitment/candidates/{uuid}/cv} — multipart upload.
 *       Stores the file in SharePoint via {@link CvUploadService}, marks it current,
 *       and queues a {@link AiArtifactKind#CV_EXTRACTION} artifact for AI extraction.
 *       Returns 201 with the persisted {@link CandidateCv} as a {@link CandidateCvResponse}.</li>
 *   <li>{@code GET /api/recruitment/candidates/{uuid}/cv/extraction} — returns
 *       the latest CV_EXTRACTION artifact (state machine: GENERATING → GENERATED →
 *       REVIEWED/OVERRIDDEN/FAILED).</li>
 * </ul>
 *
 * <p>Both endpoints enforce record-level access via
 * {@link RecruitmentRecordAccessService#canSeeCandidate(Candidate, String)} — when an
 * actor outside the recruitment:admin / recruitment:offer scope is not the candidate
 * owner and has no application visibility, we deliberately return 404 (not 403) to
 * avoid leaking the existence of candidates the caller cannot see (per the security
 * fix in commit c388184).</p>
 *
 * <p>Note on response shape: the GET endpoint returns the {@link AiArtifact} entity
 * directly per the Slice 2 plan; Phase H (Task 33) introduces a proper response DTO.
 * This keeps the slice scope tight while the plan's snapshot tests remain stable.</p>
 */
@Path("/api/recruitment/candidates/{uuid}/cv")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class CandidateCvResource {

    @Inject CvUploadService upload;
    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject RequestHeaderHolder header;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({"recruitment:write"})
    public Response upload(@PathParam("uuid") String uuid,
                           @RestForm("file") FileUpload file) throws IOException {
        String actor = header.getUserUuid();
        Candidate candidate = Candidate.findById(uuid);
        if (candidate == null || !recordAccess.canSeeCandidate(candidate, actor)) {
            throw new NotFoundException("Candidate " + uuid);
        }
        if (file == null || file.fileName() == null || file.fileName().isBlank()) {
            throw new BadRequestException("file required");
        }

        byte[] data = Files.readAllBytes(file.uploadedFile());
        String filename = file.fileName();
        String contentType = file.contentType();

        try {
            CandidateCv cv = upload.upload(uuid, filename, contentType, data, actor);
            return Response.status(201).entity(CandidateCvResponse.from(cv)).build();
        } catch (IllegalArgumentException ex) {
            // CvFileExtractor rejects unsupported extensions / oversized / empty payloads
            // with IllegalArgumentException; map to 400 per spec §6.3 contract.
            throw new BadRequestException(ex.getMessage());
        }
    }

    @GET
    @Path("/extraction")
    @RolesAllowed({"recruitment:read"})
    public AiArtifact getExtraction(@PathParam("uuid") String uuid) {
        String actor = header.getUserUuid();
        Candidate candidate = Candidate.findById(uuid);
        if (candidate == null || !recordAccess.canSeeCandidate(candidate, actor)) {
            throw new NotFoundException("Candidate " + uuid);
        }

        // Return the most recent CV_EXTRACTION artifact for this candidate.
        // Ordering by uuid is sufficient because UUIDs are not chronological — but
        // CvUploadService stamps the artifact UUID back onto the CV row, and the
        // current CV is unique per candidate, so we can fetch via that join.
        CandidateCv currentCv = CandidateCv.find(
                "candidateUuid = ?1 and isCurrent = true", uuid).firstResult();
        if (currentCv == null || currentCv.extractionArtifactUuid == null) {
            throw new NotFoundException("no CV extraction for candidate " + uuid);
        }
        AiArtifact artifact = AiArtifact.findById(currentCv.extractionArtifactUuid);
        if (artifact == null
                || artifact.subjectKind != AiSubjectKind.CANDIDATE
                || !AiArtifactKind.CV_EXTRACTION.name().equals(artifact.kind)) {
            throw new NotFoundException("no CV extraction for candidate " + uuid);
        }
        return artifact;
    }
}
