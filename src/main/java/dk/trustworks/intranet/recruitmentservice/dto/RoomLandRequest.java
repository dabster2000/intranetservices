package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.ScorecardRecommendation;

import java.util.List;
import java.util.Map;

/**
 * Body for {@code POST /recruitment/interviews/{uuid}/land} — the room's
 * atomic submit (room spec 2026-08-26 §5.3): ONE transaction appends
 * {@code SCORECARD_SUBMITTED} (unchanged shape — scores, recommendation,
 * prose in pii), one {@code NOTE_ADDED} per confirmed fact, and deletes
 * the caller's draft row. Partial success is not a state that exists.
 * <p>
 * For interview kinds that take no scorecard (INFORMAL, OFFER), scores
 * and recommendation stay null and the prose lands as a plain note
 * instead — the facts behave identically.
 *
 * @param scores         attribute code → 1..4; required for ROUND kinds
 * @param recommendation required for ROUND kinds
 * @param notes          the assembled prose (event pii only, ≤ 2000 chars)
 * @param facts          the facts the land sheet confirmed (§5.3 step 5)
 */
public record RoomLandRequest(
        Map<String, Integer> scores,
        ScorecardRecommendation recommendation,
        String notes,
        List<LandFact> facts
) {

    /**
     * One fact leaving the land sheet.
     *
     * @param field        vocabulary key
     * @param value        the stated text (pii); null only when {@code asked}
     * @param confirmed    restated / settled — {@code payload.confirmed=true}
     * @param asked        raised but nothing usable — {@code payload.outcome='ASKED'}
     * @param suggestionId when the fact came from an accepted AI chip: the
     *                     suggestion id from the suggest response — the
     *                     write then also appends
     *                     {@code AI_SUGGESTION_RESOLVED} (spec §5.4: a
     *                     model never writes a fact; acceptance is a human
     *                     action and is recorded)
     */
    public record LandFact(String field, String value, boolean confirmed, boolean asked,
                           String suggestionId) {
    }
}
