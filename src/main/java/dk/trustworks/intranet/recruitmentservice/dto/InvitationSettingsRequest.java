package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Update the interview-invitation settings (/recruitment/settings).
 *
 * <p><b>Null means "leave unchanged", empty means "clear".</b> Same partial
 * contract as {@link EmailSettingsRequest} beside it, for the same reason:
 * the settings page posts each form on its own, and treating an absent field
 * as a blanking instruction would let one form wipe another's value.
 *
 * <p>An empty {@code visitingAddress} is legal and means "invitations carry
 * no address and no arrival instructions" — the pre-feature behaviour, kept
 * reachable on purpose.
 */
public record InvitationSettingsRequest(
        String visitingAddress
) {
}
