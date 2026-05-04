package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /recruitment/candidates/{uuid}/dossier/send-signature}.
 * Recipient is implied by the dossier's signers configuration; no {@code to}
 * field is accepted. Optional {@link #note} is forwarded as the NextSign case
 * description.
 */
public record SendSignatureRequest(
        @Size(max = 2000) String note
) {
}
