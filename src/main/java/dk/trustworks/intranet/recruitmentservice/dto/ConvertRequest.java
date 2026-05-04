package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for {@code POST /recruitment/candidates/{uuid}/convert}.
 * <p>
 * The payload mirrors the minimum information needed to provision an employee
 * via {@link dk.trustworks.intranet.aggregates.users.services.UserService#createUser}
 * plus the recruitment-specific lifecycle attributes that the candidate
 * does not already carry:
 * <ul>
 *   <li>{@link #username} — login handle (Trustworks AD principal)</li>
 *   <li>{@link #email} — Trustworks email address (typically
 *       {@code firstname.lastname@trustworks.dk}; the candidate's
 *       application email may differ and is not reused)</li>
 *   <li>{@link #consultantType} — the new employee's consultant track</li>
 *   <li>{@link #careerLevel} + {@link #careerTrack} — feeds the initial
 *       {@code UserCareerLevel} row</li>
 *   <li>{@link #teamUuid} + {@link #teamMemberType} — feeds the initial
 *       {@code TeamRole} row</li>
 *   <li>{@link #plannedStartDate} — used as
 *       {@code UserStatus.statusdate} for the {@code PREBOARDING} row and as
 *       {@code activeFrom} for the career-level row</li>
 *   <li>{@link #allocation} — full-time equivalent percentage (0–100)</li>
 * </ul>
 * <p>
 * The candidate's first/last name are read from the {@code RecruitmentCandidate}
 * itself, so they are NOT duplicated in this DTO.
 */
public record ConvertRequest(
        @NotBlank(message = "username is required") @Size(max = 100) String username,
        @NotBlank(message = "email is required") @Email(message = "email must be a valid address") @Size(max = 255) String email,
        @NotNull(message = "consultantType is required") ConsultantType consultantType,
        @NotNull(message = "careerTrack is required") CareerTrack careerTrack,
        @NotNull(message = "careerLevel is required") CareerLevel careerLevel,
        @NotBlank(message = "teamUuid is required") @Size(min = 36, max = 36) String teamUuid,
        @NotNull(message = "teamMemberType is required") TeamMemberType teamMemberType,
        @NotNull(message = "plannedStartDate is required") LocalDate plannedStartDate,
        int allocation
) {
}
