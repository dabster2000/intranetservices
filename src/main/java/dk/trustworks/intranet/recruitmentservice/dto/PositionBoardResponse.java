package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Response envelope for {@code GET /recruitment/positions/{uuid}/board}
 * (P7 contract {@code IPositionBoard}): the position header facts, one
 * column per {@code stage_set} entry in set order (including HIRED), and
 * the summarized terminal rail. Visibility is enforced before this DTO is
 * built — an invisible position answers 404, so the shape never needs to
 * hide anything.
 */
public record PositionBoardResponse(
        BoardPositionSummary position,
        List<BoardColumn> columns,
        BoardTerminalSummary terminal
) {
}
