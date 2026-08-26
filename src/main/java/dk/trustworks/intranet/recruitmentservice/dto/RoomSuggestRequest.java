package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Body for {@code POST /recruitment/interviews/{uuid}/notes/suggest} —
 * the room's AI fact extraction (room spec 2026-08-26 §5.4, flagged).
 * Carries only the line texts to extract from, typically the one line the
 * caret is in; the model proposes vocabulary-keyed facts with the source
 * quote as evidence, and a HUMAN accepts each one.
 *
 * @param lines the note-line texts (interviewer's own words)
 */
public record RoomSuggestRequest(List<String> lines) {
}
