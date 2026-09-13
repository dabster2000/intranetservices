package dk.trustworks.intranet.aggregates.crm.account.dto;

/**
 * A partial change to an account. Every field is optional; a null field is "leave it
 * alone", which is why the primitives are boxed and why clearing a value needs the
 * explicit flags below rather than a null.
 *
 * <p>{@code bandNote} is only read when {@code band} is present.
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
        String accountTeamBubbleUuid,
        boolean clearAccountTeamBubble) {
}
