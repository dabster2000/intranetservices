package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Body for {@code POST /recruitment/interviews/{uuid}/facts} — the room's
 * LIVE fact capture (⌥↵, room spec 2026-08-26 §5.2). Exists as a
 * room-scoped sibling of {@code POST /candidates/{uuid}/notes} because the
 * per-person key here is the interview ASSIGNMENT, not candidate profile
 * visibility: a restricted interviewer capturing "three months' notice"
 * mid-interview is the feature (decision 6, §7.1), and the notes route's
 * candidate-boundary gate would refuse them.
 *
 * @param field        vocabulary key
 * @param value        the stated text (event pii); null only when {@code asked}
 * @param confirmed    restated / settled — {@code payload.confirmed=true}
 * @param asked        raised but nothing usable — {@code payload.outcome='ASKED'}
 * @param suggestionId when accepting an AI chip: the suggestion id from the
 *                     suggest response; the write then also appends
 *                     {@code AI_SUGGESTION_RESOLVED} (§5.4)
 */
public record RoomFactRequest(String field, String value, boolean confirmed, boolean asked,
                              String suggestionId) {
}
