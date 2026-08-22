package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code PUT /recruitment/candidates/{uuid}/notes/{eventId}} —
 * the author's correction of their own discussion note (change request
 * 2026-08-22). The event stream stays append-only: the edit is recorded as
 * a {@code NOTE_EDITED} event whose pii carries the new text, and the
 * timeline read path folds the newest edit into the displayed note. Only
 * the text can change — privacy, mentions and the structured-field marker
 * are fixed at posting time.
 *
 * @param text the corrected note body (PII by definition)
 */
public record NoteEditRequest(
        @NotBlank(message = "text is required") @Size(max = 65535) String text
) {
}
