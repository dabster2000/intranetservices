package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /recruitment/applications/{uuid}/stage} —
 * advance or move back (spec §6.2). The target must be part of the
 * position's {@code stage_set}; direction is derived server-side, back
 * moves are flagged {@code direction=BACK} on the event, and forward
 * skips require recruiter/owner rights (spec §4.2 invariant 1).
 */
public record ApplicationStageRequest(
        @NotNull(message = "stage is required")
        RecruitmentStage stage
) {
}
