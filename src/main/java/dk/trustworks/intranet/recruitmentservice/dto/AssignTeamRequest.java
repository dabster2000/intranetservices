package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /recruitment/applications/{uuid}/assign-team}
 * — the offer-stage team decision on practice-level positions. Any existing
 * team is accepted, including teams of other practices and practice-less
 * teams (spec §4.1: the position's practice is a grouping attribute, never
 * a constraint on team choice).
 */
public record AssignTeamRequest(
        @NotBlank(message = "teamUuid is required")
        String teamUuid
) {
}
