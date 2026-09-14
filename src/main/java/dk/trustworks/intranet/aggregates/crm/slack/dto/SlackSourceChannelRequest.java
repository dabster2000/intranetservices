package dk.trustworks.intranet.aggregates.crm.slack.dto;

/**
 * What somebody typed into "Add a channel" (spec §6.1).
 *
 * <p>One free-text field, because the two things an admin has to hand are not the same
 * thing: a {@code #name} copied out of Slack's sidebar and a {@code C0AD6RSD3UG} copied out
 * of a channel's link. Which of the two it is, is Slack's question to answer and not the
 * caller's to declare — {@code SlackService.describeChannel} resolves either — so a second
 * field naming the kind would only be a way for the two to disagree.
 */
public record SlackSourceChannelRequest(String channel) {
}
