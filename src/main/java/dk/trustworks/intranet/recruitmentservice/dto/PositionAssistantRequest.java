package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for assigning a recruitment assistant to a position
 * ({@code POST /recruitment/positions/{uuid}/assistants}), the record-level
 * scope of the {@code RECRUITMENT_ASSISTANT} role since the 2026-09-08
 * position-scoping design (D1).
 * <p>
 * Deliberately carries nothing but the employee: an assignment has no role,
 * no scope qualifier and no expiry. It is active or revoked, and the position
 * it is on comes from the path. Anything richer would be a second permission
 * model living beside the role.
 *
 * @param userUuid the employee to assign ({@code users.uuid}). Holding the
 *                 {@code RECRUITMENT_ASSISTANT} role is NOT validated here —
 *                 an assignment for somebody without the role is inert rather
 *                 than an error, which keeps grant order free (assign first,
 *                 grant the role after, or the reverse).
 */
public record PositionAssistantRequest(
        @NotBlank(message = "userUuid is required") @Size(min = 36, max = 36) String userUuid
) {
}
