package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSecurityClearance;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Request body for creating or updating a {@code RecruitmentCandidate}.
 * <p>
 * Two create paths share this record (plan §P3):
 * <ul>
 *   <li><b>Dossier path</b> ({@link #templateUuid} present): the original
 *       "+ New candidate with offer dossier" flow — {@code email} and
 *       {@code targetCompanyUuid} are required (service-enforced; the DB
 *       columns are nullable since V435 for the ATS path's sake).</li>
 *   <li><b>ATS path</b> ({@link #templateUuid} absent): a standalone
 *       candidate/talent-pool entry — {@link #source} is mandatory,
 *       everything else optional (a LinkedIn paste import may know only
 *       the name).</li>
 * </ul>
 * On PUT update, only supplied fields are applied — null fields are left
 * unchanged. {@code templateUuid} is ignored on update.
 * <p>
 * Deliberately absent: {@code lawfulBasis}, {@code art14*},
 * {@code retentionDeadline}, {@code poolStatus} — GDPR bookkeeping and pool
 * state are system-maintained (create policy / pool endpoints), never
 * client-supplied.
 */
public record CandidateRequest(
        @NotBlank(message = "firstName is required") @Size(max = 100) String firstName,
        @NotBlank(message = "lastName is required") @Size(max = 100) String lastName,
        @Email(message = "email must be a valid address") @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 500) String linkedinUrl,
        @Size(min = 36, max = 36) String targetCompanyUuid,
        LocalDate targetStartDate,
        @Size(max = 65535) String notes,
        @Size(min = 36, max = 36) String templateUuid,
        CandidateSource source,
        Map<String, Object> sourceDetail,
        @Size(min = 36, max = 36) String referredByUserUuid,
        @Size(max = 200) String externalReferrerName,
        @Size(min = 36, max = 36) String sponsoringPartnerUuid,
        @Size(min = 36, max = 36) String relevantTeamleadUuid,
        List<@Size(max = 50) String> tags,
        CandidateEducationLevel educationLevel,
        @Size(max = 200) String educationOther,
        CandidateExperienceLevel experienceLevel,
        List<@Size(max = 100) String> specializations,
        CandidateSecurityClearance securityClearance,
        Boolean securityRelevant,
        List<@Size(max = 120) String> languages,
        @Size(max = 200) String currentEmployer
) {
}
