package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CandidateResponse(
        String uuid,
        String firstName,
        String lastName,
        String email,
        String phone,
        String targetCompanyUuid,
        LocalDate targetStartDate,
        String notes,
        CandidateStatus status,
        String declineReason,
        String convertedUserUuid,
        String sharepointFolderPath,
        SharePointMoveStatus sharepointMoveStatus,
        String createdByUseruuid,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        RevisionSummary latestRevision
) {
}
