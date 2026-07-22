package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentCircleRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for adding a member to a partner-track position's circle
 * ({@code POST /recruitment/positions/{uuid}/circle}).
 *
 * @param userUuid     the employee to grant visibility ({@code users.uuid})
 * @param roleInCircle optional; defaults to {@code PARTICIPANT}
 */
public record CircleMemberRequest(
        @NotBlank(message = "userUuid is required") @Size(min = 36, max = 36) String userUuid,
        RecruitmentCircleRole roleInCircle
) {
}
