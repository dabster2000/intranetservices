package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for withdrawing a candidate (the candidate themselves backed
 * out). Same shape as {@code DeclineRequest} but kept as a distinct type so
 * the resource layer can dispatch to {@code RecruitmentCandidate.withdraw}
 * vs {@code .decline} based purely on the resource path.
 */
public record WithdrawRequest(
        @NotBlank(message = "reason is required") @Size(max = 2000) String reason
) {
}
