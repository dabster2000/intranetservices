package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /recruitment/applications/{uuid}/move-position} — the
 * re-filing command that corrects a wrong req without splitting the pipeline
 * run.
 * <p>
 * Only the destination is supplied: the landing stage is derived server-side
 * from the target position's stage set (kept when it contains the current
 * stage, clamped backwards otherwise), because letting the caller pick both
 * would make "move" a disguised fast-track.
 * <p>
 * Bean validation is inert in this backend (house rule) — the annotation is
 * documentation; {@code RecruitmentApplicationResource} enforces it
 * explicitly.
 */
public record ApplicationMovePositionRequest(
        @NotBlank String positionUuid) {
}
