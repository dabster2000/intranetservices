package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * One kanban column (P7 contract {@code IBoardColumn}): a stage code from
 * the position's ordered {@code stage_set} and its open applications,
 * oldest {@code stageEnteredAt} first — the longest-waiting card sits on
 * top. Stages with no open applications still appear (empty column), so
 * the board always renders the full pipeline shape.
 */
public record BoardColumn(String stage, List<BoardCard> applications) {
}
