package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /recruitment/applications/{uuid}/withdraw}
 * — the candidate backed out. The optional note (how/why they withdrew)
 * lands in the {@code APPLICATION_WITHDRAWN} event's {@code pii} block.
 */
public record ApplicationWithdrawRequest(
        @Size(max = 2000, message = "note must be at most 2000 characters")
        String note
) {
}
