package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for declining a candidate. The reason is required so reporting
 * has a non-empty rationale; lengths are capped to match the database column
 * (which is {@code TEXT} but bounded here for input hygiene).
 */
public record DeclineRequest(
        @NotBlank(message = "reason is required") @Size(max = 2000) String reason
) {
}
