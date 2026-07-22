package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
 * @param field       optional structured marker. The only defined value in
 *                    P3 is {@code SALARY_EXPECTATION}, which requires the
 *                    {@code recruitment:comp} scope (spec §4.1: salary is a
 *                    note, never a column)
 */
public record NoteRequest(
        @NotBlank(message = "text is required") @Size(max = 65535) String text,
        Boolean isPrivate,
        @Size(max = 50) String field
) {
    /** The structured note field for salary expectations (comp-scoped). */
    public static final String FIELD_SALARY_EXPECTATION = "SALARY_EXPECTATION";
}
