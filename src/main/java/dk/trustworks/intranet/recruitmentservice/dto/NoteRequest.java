package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body for {@code POST /recruitment/candidates/{uuid}/notes}. Notes are
 * events, not state rows — the text lands exclusively in the event's
 * {@code pii} block (spec §3.4, §4.1: free-text personal content lives only
 * in events).
 *
 * @param text        the note body (PII by definition)
 * @param isPrivate   {@code true} = visible to author + recruiter + admin
 *                    only (spec §7.2 field gate); recorded as
 *                    {@code payload.private}
 * @param field       optional structured marker — a
 *                    {@code RecruitmentFactVocabulary} key (Interview Room
 *                    spec §4.2 widened the P3 single-entry list). The
 *                    compensation group requires the {@code recruitment:comp}
 *                    scope (spec §4.1: salary is a note, never a column)
 * @param mentions    optional {@code users.uuid} list of colleagues to
 *                    notify (Slack DM). Employee identifiers, not candidate
 *                    data — recorded as {@code payload.mentions}; unknown
 *                    uuids are dropped silently. Max 20.
 * @param outcome     optional; {@code "ASKED"} marks a fact question that
 *                    was raised and got nothing usable (Interview Room spec
 *                    §4.3 — the question was spent). Recorded as
 *                    {@code payload.outcome}; only valid with a fact field.
 * @param confirmed   optional; {@code true} marks the value as restated /
 *                    settled in the offer conversation — the only state an
 *                    offer should rely on. Recorded as
 *                    {@code payload.confirmed}; only valid with a fact field.
 * @param interviewUuid optional provenance: the interview the fact was said
 *                    in (structural — recorded as
 *                    {@code payload.interview_uuid}); set by the Interview
 *                    Room's fact capture.
 */
public record NoteRequest(
        @NotBlank(message = "text is required") @Size(max = 65535) String text,
        Boolean isPrivate,
        @Size(max = 50) String field,
        @Size(max = 20) List<String> mentions,
        @Size(max = 12) String outcome,
        Boolean confirmed,
        @Size(max = 36) String interviewUuid
) {
    /** The structured note field for salary expectations (comp-scoped). */
    public static final String FIELD_SALARY_EXPECTATION = "SALARY_EXPECTATION";

    /** The one defined {@code outcome} marker (spec §4.3). */
    public static final String OUTCOME_ASKED = "ASKED";

    /** The pre-Interview-Room shape — existing callers stay source-compatible. */
    public NoteRequest(String text, Boolean isPrivate, String field, List<String> mentions) {
        this(text, isPrivate, field, mentions, null, null, null);
    }
}
