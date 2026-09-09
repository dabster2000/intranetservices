package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 *   <li>{@link #username} — login handle (Trustworks AD principal). Capped at
 *       50 characters because {@code user.username} is {@code varchar(50)};
 *       a longer bound would let an over-long handle reach the database as a
 *       truncation/constraint error instead of a clean 400. Shaped by
 *       {@code ^[a-z0-9._@-]+$} (in practice lowercase {@code firstname.lastname}) because
 *       Azure resolves the login name verbatim: a handle carrying a space or
 *       an uppercase letter provisions an account nobody can sign in to. The
 *       hire dialog enforces the same rule, but the endpoint is reachable by
 *       any {@code recruitment:write} client-credentials holder, so the shape
 *       has to be part of the API contract rather than of one caller.</li>
 *   <li>{@link #email} — Trustworks email address (typically
 *       {@code firstname.lastname@trustworks.dk}; the candidate's
 *       application email may differ and is not reused). Capped at 150 to
 *       match {@code user.email varchar(150)}, for the same reason the
 *       username is capped at 50.</li>
 *   <li>{@link #consultantType} — the new employee's consultant track</li>
 *   <li>{@link #careerLevel} + {@link #careerTrack} — feeds the initial
 *       {@code UserCareerLevel} row. The pair must be consistent: the
 *       conversion rejects a level that belongs to another track.</li>
 *   <li>{@link #teamUuid} + {@link #teamMemberType} — feeds the initial
 *       {@code TeamRole} row</li>
 *   <li>{@link #plannedStartDate} — used as
 *       {@code UserStatus.statusdate} for the {@code PREBOARDING} row and as
 *       {@code activeFrom} for the career-level row</li>
 *   <li>{@link #allocation} — contracted <b>hours per week</b> (37 = Danish
 *       full-time, 20 = a typical student contract)</li>
 *   <li>{@link #salaryType} — {@code NORMAL} (monthly) or {@code HOURLY}
 *       (students). OPTIONAL: omitting it defaults to {@code NORMAL} in
 *       {@code CandidateConversionUseCase}, so callers written against the
 *       pre-{@code salaryType} contract keep working unchanged.</li>
 * </ul>
 * <p>
 * The candidate's first/last name and phone number are read from the
 * {@code RecruitmentCandidate} itself, so they are NOT duplicated in this DTO.
 */
public record ConvertRequest(
        @NotBlank(message = "username is required")
        @Size(max = 50)
        @Pattern(regexp = "^[a-z0-9._@-]+$",
                message = "username must be a lowercase handle without spaces (a-z, 0-9, dot, underscore, @ and hyphen)")
        String username,
        @NotBlank(message = "email is required") @Email(message = "email must be a valid address") @Size(max = 150) String email,
        @NotNull(message = "consultantType is required") ConsultantType consultantType,
        @NotNull(message = "careerTrack is required") CareerTrack careerTrack,
        @NotNull(message = "careerLevel is required") CareerLevel careerLevel,
        @NotBlank(message = "teamUuid is required") @Size(min = 36, max = 36) String teamUuid,
        @NotNull(message = "teamMemberType is required") TeamMemberType teamMemberType,
        @NotNull(message = "plannedStartDate is required") LocalDate plannedStartDate,
        @Min(value = 1, message = "salary must be at least 1") int salary,
        int allocation, // contracted hours per week (37 = Danish full-time, 20 = student)
        SalaryType salaryType // optional; null means NORMAL (see class javadoc)
) {

    /**
     * Source-compatible constructor for callers written before {@code salaryType}
     * existed. Delegates with a null {@code salaryType}, which the conversion use
     * case resolves to {@link SalaryType#NORMAL}.
     */
    public ConvertRequest(String username, String email, ConsultantType consultantType,
                          CareerTrack careerTrack, CareerLevel careerLevel, String teamUuid,
                          TeamMemberType teamMemberType, LocalDate plannedStartDate,
                          int salary, int allocation) {
        this(username, email, consultantType, careerTrack, careerLevel, teamUuid,
                teamMemberType, plannedStartDate, salary, allocation, null);
    }
}
