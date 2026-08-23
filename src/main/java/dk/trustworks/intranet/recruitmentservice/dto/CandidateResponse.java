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

/**
 * The candidate read DTO.
 *
 * @param applicationUuid the application created <em>by this very call</em>
 *        when the atomic create-with-position path ran
 *        ({@code CandidateRequest.positionUuid} supplied); {@code null} on
 *        every other response — reads, updates, and positionless creates.
 *        <p>This is deliberately NOT "the candidate's current application":
 *        it is a create receipt, so the caller can navigate straight to the
 *        new pipeline card without a second round trip. Anything wanting the
 *        candidate's applications reads
 *        {@code GET /recruitment/candidates/{uuid}/applications}, which is
 *        per-viewer filtered.
 */
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
        List<String> languages,
        String currentEmployer,
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
        RevisionSummary latestRevision,
        String applicationUuid
) {

    /**
     * Copy for viewers who may read the candidate profile but not the offer
     * dossier. Target-company/start facts, the dossier-era notes field,
     * SharePoint hand-off state, and revision kind/version/timestamp must not
     * cross that narrower boundary.
     */
    public CandidateResponse withoutDossierMetadata() {
        if (targetCompanyUuid == null
                && targetStartDate == null
                && notes == null
                && sharepointFolderPath == null
                && sharepointMoveStatus == null
                && latestRevision == null) {
            return this;
        }
        return new CandidateResponse(
                uuid,
                firstName,
                lastName,
                email,
                phone,
                linkedinUrl,
                null,
                null,
                null,
                status,
                poolStatus,
                source,
                sourceDetail,
                referredByUserUuid,
                externalReferrerName,
                sponsoringPartnerUuid,
                relevantTeamleadUuid,
                tags,
                educationLevel,
                educationOther,
                experienceLevel,
                specializations,
                securityClearance,
                securityRelevant,
                languages,
                currentEmployer,
                lawfulBasis,
                art14Required,
                art14Deadline,
                declineReason,
                convertedUserUuid,
                null,
                null,
                createdByUseruuid,
                createdAt,
                updatedAt,
                null,
                applicationUuid);
    }
}
