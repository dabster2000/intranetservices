package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateCv;

import java.time.LocalDateTime;

/**
 * Response DTO for {@link CandidateCv} returned by the CV upload endpoint
 * (spec §6.3 — POST /api/recruitment/candidates/{uuid}/cv).
 *
 * <p>Reads only safe-to-expose fields off the entity. The trigger-derived
 * {@code current_for_unique} column is deliberately omitted from the contract.
 */
public record CandidateCvResponse(
        String uuid,
        String candidateUuid,
        String fileUrl,
        String fileSha256,
        boolean isCurrent,
        String uploadedByUuid,
        LocalDateTime uploadedAt,
        String extractionArtifactUuid) {

    public static CandidateCvResponse from(CandidateCv cv) {
        return new CandidateCvResponse(
                cv.uuid,
                cv.candidateUuid,
                cv.fileUrl,
                cv.fileSha256,
                cv.isCurrent,
                cv.uploadedByUuid,
                cv.uploadedAt,
                cv.extractionArtifactUuid);
    }
}
