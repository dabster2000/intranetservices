package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The room's AI reading of the notes (room spec 2026-08-26 §5.4, amended
 * 2026-08-27). One call now does two jobs over the same lines:
 * <ul>
 *   <li><b>Facts</b> — ephemeral proposals. Nothing is written until a
 *       human accepts one, and acceptance rides the fact write
 *       ({@code suggestionId} on the capture/land call, which appends
 *       {@code AI_SUGGESTION_RESOLVED}). The model never writes a fact.</li>
 *   <li><b>Subject tags</b> — which scorecard subject a line is evidence
 *       for. This is note metadata only: it never reaches the ledger, so
 *       the room applies it directly and the interviewer overrides it with
 *       ⌘1–⌘6. Nothing to un-write, hence no acceptance step.</li>
 * </ul>
 * Both are anchored by {@code lineIndex} — the position of the line in the
 * request's {@code lines} array, validated in range server-side so a
 * hallucinated index can never point at a line the caller did not send.
 *
 * @param suggestions the proposed facts, each with its source quote
 * @param subjectTags the per-line subject classification
 */
public record RoomSuggestResponse(List<RoomFactSuggestion> suggestions,
                                  List<RoomSubjectTag> subjectTags) {

    /**
     * @param id        suggestion id — echo it back on acceptance
     * @param lineIndex index into the request's {@code lines} array
     * @param field     vocabulary key
     * @param value     proposed value text
     * @param evidence  the interviewer's own words the proposal rests on
     */
    public record RoomFactSuggestion(String id, int lineIndex, String field,
                                     String value, String evidence) {
    }

    /**
     * @param lineIndex   index into the request's {@code lines} array
     * @param subjectCode a code from the interview's own scorecard template —
     *                    anything else is dropped before it reaches the room
     */
    public record RoomSubjectTag(int lineIndex, String subjectCode) {
    }
}
