package dk.trustworks.intranet.aggregates.crm.account.dto;

/**
 * A partial change to an account. Every field is optional; a null field is "leave it
 * alone", which is why the primitives are boxed and why clearing a value needs the
 * explicit flags below rather than a null.
 *
 * <p>{@code bandNote} is only read when {@code band} is present.
 *
 * <p><b>{@code ownerUuid} is here so "Start pursuing" is one call.</b> A Strategic or
 * Active account must have an owner, so promoting an unowned account used to be a 409
 * ("set the account manager first") and a round trip to the client form. The band and the
 * owner arrive together now, and the service writes {@code client.accountmanager} — which
 * is still the single store for the owner. The account-team bubble link that used to live
 * here went with V598/V599; the team is people, and people are
 * {@link AccountRolesRequest}.
 */
public record AccountPatchRequest(
        String band,
        String bandNote,
        String nextStep,
        boolean clearNextStep,
        String slackSpace,
        boolean clearSlackSpace,
        String gtmBubbleUuid,
        boolean clearGtmBubble,
        /** The account manager — written to {@code client.accountmanager}. */
        String ownerUuid,
        boolean clearOwner) {
}
