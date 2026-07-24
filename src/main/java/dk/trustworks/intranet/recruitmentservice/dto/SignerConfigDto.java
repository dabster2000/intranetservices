package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Wire representation of a single signer entry on a dossier draft. Mirrors the
 * shape of {@code template_default_signers} columns plus a {@code signingSchema}
 * reference, so the client can edit the dossier's signers free-form before
 * sending for signature.
 *
 * @param group         1-based signing group as a decimal string ("1", "2", …),
 *                      mirroring {@code template_default_signers.signer_group}:
 *                      signers sharing a group sign in parallel, lower groups
 *                      sign before higher ones. Blank or non-numeric values are
 *                      read as group 1 at send time. NOT a free-text label —
 *                      writing one here collapses the signer into the first
 *                      round.
 * @param name          display name presented to the recipient
 * @param email         signer email — required for any signer
 * @param signing       true if this party performs an actual signature; false
 *                      if they only receive the document for review
 * @param needsCpr      whether the signer must verify identity with CPR
 * @param role          human-readable role label (e.g. "Manager", "Candidate")
 * @param signingSchema NextSign signing-schema identifier; nullable for
 *                      review-only recipients
 */
public record SignerConfigDto(
        String group,
        String name,
        @NotBlank(message = "email is required") @Email(message = "email must be a valid address") String email,
        boolean signing,
        boolean needsCpr,
        String role,
        String signingSchema
) {
}
