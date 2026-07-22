package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /recruitment/applications/{uuid}/reject}.
 * The coded reason is mandatory (reporting aggregates on it — spec §4.2
 * invariant 4); the optional free-text note lands in the
 * {@code APPLICATION_REJECTED} event's {@code pii} block, never in a state
 * table.
 */
public record ApplicationRejectRequest(
        @NotNull(message = "reasonCode is required — pick the closest coded reason; elaborate in the note")
        RecruitmentRejectionReason reasonCode,

        @Size(max = 2000, message = "note must be at most 2000 characters")
        String note
) {
}
