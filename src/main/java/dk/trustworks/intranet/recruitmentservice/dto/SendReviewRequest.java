package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /recruitment/candidates/{uuid}/dossier/send-review}.
 * <p>
 * <strong>Recipient lock:</strong> the spec (§8.2) requires that the recipient
 * is always the candidate's email — there is intentionally NO {@code to}
 * field on this DTO. Any caller-supplied recipient is ignored by the backend.
 *
 * @param note optional free-text note appended to the review email body
 */
public record SendReviewRequest(
        @Size(max = 2000) String note
) {
}
