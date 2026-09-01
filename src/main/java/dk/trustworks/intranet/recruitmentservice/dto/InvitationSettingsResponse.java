package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * The interview-invitation settings shown on /recruitment/settings.
 *
 * <p>One setting today: the visiting address the candidate's calendar
 * invitation tells them to turn up at. It is deliberately NOT the
 * registered/postal address used by the expense geofence and the bulk-mail
 * footer — those answer a different question and are not this value's
 * source.
 *
 * @param visitingAddress          the address invitations carry; empty means
 *                                 invitations carry no address and no
 *                                 arrival instructions at all
 * @param visitingAddressDefault   the built-in address, so the page can offer
 *                                 "restore the default" without a second
 *                                 round-trip
 * @param visitingAddressIsDefault true while nobody has edited it — the page
 *                                 shows the built-in value unchanged
 */
public record InvitationSettingsResponse(
        String visitingAddress,
        String visitingAddressDefault,
        boolean visitingAddressIsDefault
) {
}
