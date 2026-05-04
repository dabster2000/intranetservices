package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Full read model for a single candidate, returned by
 * {@code GET /recruitment/candidates/{uuid}}. Includes status fields, lifecycle
 * timestamps, the SharePoint move queue marker, and a one-line summary of the
 * latest dossier revision (or {@code null} if none exists yet).
 */
public record CandidateResponse(
        String uuid,
        String firstName,
        String lastName,
        String email,
        String phone,
        String targetCompanyUuid,
        LocalDate targetStartDate,
        String notes,
        String status,
        String declineReason,
        String convertedUserUuid,
        String sharepointFolderPath,
        String sharepointMoveStatus,
        String createdByUseruuid,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        RevisionSummary latestRevision
) {
}
