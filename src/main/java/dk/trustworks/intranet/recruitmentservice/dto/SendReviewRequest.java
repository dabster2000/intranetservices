package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /recruitment/candidates/{uuid}/dossier/send-review}
 * and {@code .../generate-review-pdf}.
 * <p>
 * <strong>Recipient lock:</strong> the spec (§8.2) requires that the recipient
 * is always the candidate's email — there is intentionally NO {@code to}
 * field on this DTO. Any caller-supplied recipient is ignored by the backend.
 * <p>
 * <strong>Note semantics:</strong> for {@code send-review} the note IS the
 * email body (no greeting/sign-off) and the resource enforces non-blank;
 * for {@code generate-review-pdf} the note is just persisted on the revision
 * row for audit and may be blank.
 *
 * <strong>Note format:</strong> {@code noteFormat} says whether {@code note}
 * is legacy plain text or a rich-text HTML fragment. Absent means PLAIN, so a
 * client predating rich text produces exactly the email it always did.
 *
 * @param note       free-text message; constraints depend on the calling endpoint
 * @param noteFormat PLAIN (default) or HTML
 */
public record SendReviewRequest(
        @Size(max = 4000) String note,
        String noteFormat
) {
    /** Back-compat constructor for callers that predate rich text. */
    public SendReviewRequest(String note) {
        this(note, null);
    }
}
