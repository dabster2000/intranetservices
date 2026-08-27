package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The room's AI fact proposals (room spec 2026-08-26 §5.4): ephemeral
 * chips — nothing is written until a human accepts one, and acceptance
 * rides the fact write ({@code suggestionId} on the capture/land call,
 * which appends {@code AI_SUGGESTION_RESOLVED}).
 *
 * @param suggestions the proposed facts, each with its source quote
 */
public record RoomSuggestResponse(List<RoomFactSuggestion> suggestions) {

    /**
     * @param id       suggestion id — echo it back on acceptance
     * @param field    vocabulary key
     * @param value    proposed value text
     * @param evidence the interviewer's own words the proposal rests on
     * @param flag     arithmetic inconsistency, when detected (§5.4 — e.g.
     *                 a start date the notice period cannot reach); null
     *                 otherwise
     */
    public record RoomFactSuggestion(String id, String field, String value,
                                     String evidence, String flag) {
    }
}
