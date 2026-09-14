package dk.trustworks.intranet.aggregates.crm.slack.dto;

/**
 * The pause switch on one listed channel (spec §6.1).
 *
 * <p>Boxed rather than primitive, for the reason {@code CalendarConsentRequest} is: a body
 * that forgot the field would otherwise read as "off" and quietly stop a channel somebody
 * meant to leave running, and a lane that has gone quiet is the hardest kind of wrong to
 * notice.
 */
public record SlackSourceChannelPatchRequest(Boolean enabled) {
}
