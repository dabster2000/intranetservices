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
 * unchanged. {@code templateUuid} is ignored on update. The dossier-era
 * fields {@code targetCompanyUuid}, {@code targetStartDate} and {@code notes}
 * require the HR/admin dossier-write capability; ordinary candidate fields
 * and tags remain writable through the normal recruitment gate.
 * <p>
 * Deliberately absent: {@code lawfulBasis}, {@code art14*},
 * {@code retentionDeadline}, {@code poolStatus} — GDPR bookkeeping and pool
 * state are system-maintained (create policy / pool endpoints), never
 * client-supplied.
 * <p>
 * {@link #positionUuid} is <b>create-only</b> and nullable: supplying it
 * makes the create atomic (candidate + application in one transaction,
 * {@code CandidateService.createCandidate(req, actor, position)}), leaving
 * it null keeps the positionless paths — talent pool, Airtable import,
 * public {@code /apply} and the dossier-only flow — working unchanged. It
 * is ignored on PUT update, exactly like {@code templateUuid}: moving an
 * application between positions is {@code POST
 * /recruitment/applications/{uuid}/move-position}, not a candidate edit.
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
        @Size(max = 200) String currentEmployer,
        @Size(min = 36, max = 36) String positionUuid
) {

    /**
     * Whether this request takes the <b>dossier path</b> — the HR-only
     * offer/contract surface that {@code CandidateService.createCandidate}
     * opens a {@link dk.trustworks.intranet.recruitmentservice.model.CandidateDossier}
     * for, and that go-live decision D17 denies the team lead.
     *
     * <h3>Why this lives here and nowhere else</h3>
     * The authorization gate in {@code RecruitmentResource.createCandidate}
     * and the branch in {@code CandidateService.createCandidate} MUST answer
     * the same question about the same field, or the gate protects nothing.
     * They used to answer it with two different predicates — the resource
     * with {@code trimToNull(...) != null} and the service with
     * {@code != null && !isBlank()} — and those disagree: {@code String.trim}
     * strips every character {@code <= U+0020} (so a value of pure control
     * characters trims to empty and the gate did not fire), while
     * {@code String.isBlank} only strips {@code Character.isWhitespace} (so
     * the service still treated it as a template and opened the dossier).
     * A {@code templateUuid} of {@code ""} therefore walked an
     * intake-only caller straight into the contract flow. Bean validation is
     * inert in this backend, so {@code @Size} did not stop it either.
     * <p>
     * One predicate, called from both sides, is the only shape in which that
     * class of bug cannot come back. Do not re-derive it a third time — call
     * this method.
     * <p>
     * <b>Fail-closed:</b> anything non-null that is not pure whitespace
     * counts as "a template was supplied", including junk. The gate then
     * fires and the caller gets a 403 instead of a silently opened dossier.
     */
    public boolean opensDossier() {
        return templateUuid != null && !templateUuid.isBlank();
    }

    /**
     * Whether a PATCH-style candidate update supplies any field owned by the
     * offer dossier rather than the ordinary recruitment profile. Supplying
     * an empty value still counts and therefore fails closed at the write
     * gate; {@code templateUuid} is create-only and ignored on update.
     */
    public boolean updatesDossierMetadata() {
        return targetCompanyUuid != null || targetStartDate != null || notes != null;
    }
}
