package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /recruitment/candidates/{uuid}/applications}
 * — attach a candidate to a position (spec §6.2). The application starts in
 * the first stage of the position's {@code stage_set} (always
 * {@code SCREENING} — mandatory per {@code RecruitmentStage#MANDATORY}).
 */
public record ApplicationCreateRequest(
        @NotBlank(message = "positionUuid is required — an application is always FOR a position")
        String positionUuid
) {
}
