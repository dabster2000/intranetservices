package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateLawfulBasis;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSecurityClearance;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record CandidateResponse(
        String uuid,
        String firstName,
        String lastName,
        String email,
        String phone,
        String linkedinUrl,
        String targetCompanyUuid,
        LocalDate targetStartDate,
        String notes,
        CandidateStatus status,
        CandidatePoolStatus poolStatus,
        CandidateSource source,
        Map<String, Object> sourceDetail,
        String referredByUserUuid,
        String externalReferrerName,
        String sponsoringPartnerUuid,
        String relevantTeamleadUuid,
        List<String> tags,
        CandidateEducationLevel educationLevel,
        String educationOther,
        CandidateExperienceLevel experienceLevel,
        List<String> specializations,
        CandidateSecurityClearance securityClearance,
        Boolean securityRelevant,
        CandidateLawfulBasis lawfulBasis,
        Boolean art14Required,
        LocalDateTime art14Deadline,
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
