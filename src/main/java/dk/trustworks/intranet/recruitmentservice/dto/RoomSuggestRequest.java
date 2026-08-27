package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Body for {@code POST /recruitment/interviews/{uuid}/notes/suggest} —
 * the room's AI reading of the notes (room spec 2026-08-26 §5.4, flagged).
 * <p>
 * Carries the line texts to read. Since 2026-08-27 the room sends the
 * lines it has changed since the previous sweep — a batch rather than the
 * single caret line — and the response anchors back by array position, so
 * <em>order matters and blank lines must not be dropped by the caller</em>
 * without renumbering. The allowed subject set is NOT part of this body:
 * it is derived from the interview's own scorecard template server-side,
 * so a caller cannot widen it.
 *
 * @param lines the note-line texts (interviewer's own words)
 */
public record RoomSuggestRequest(List<String> lines) {
}
