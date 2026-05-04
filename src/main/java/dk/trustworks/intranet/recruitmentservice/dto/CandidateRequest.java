package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for creating or updating a {@code RecruitmentCandidate}.
 * <p>
 * On create, all required fields must be present. On PUT/PATCH update, only
 * the supplied fields are applied — null fields are left unchanged. The
 * {@link #templateUuid} field is required on create (the "+ New candidate"
 * modal captures the candidate together with the template that will populate
 * the initial dossier) and ignored on update — once a dossier is opened the
 * template is fixed.
 */
public record CandidateRequest(
        @NotBlank(message = "firstName is required") @Size(max = 100) String firstName,
        @NotBlank(message = "lastName is required") @Size(max = 100) String lastName,
        @NotBlank(message = "email is required") @Email(message = "email must be a valid address") @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @NotBlank(message = "targetCompanyUuid is required") @Size(min = 36, max = 36) String targetCompanyUuid,
        LocalDate targetStartDate,
        @Size(max = 65535) String notes,
        @Size(min = 36, max = 36) String templateUuid
) {
}
