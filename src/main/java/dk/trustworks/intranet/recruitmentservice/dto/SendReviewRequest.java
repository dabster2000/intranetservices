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
 * @param note free-text message; constraints depend on the calling endpoint
 */
public record SendReviewRequest(
        @Size(max = 2000) String note
) {
}
