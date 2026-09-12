package dk.trustworks.intranet.aggregates.crm.signal.model.enums;

/**
 * Where a signal was captured (CRM spec §3.4).
 *
 * <p>Only {@link #INTRA} is reachable today — the "Heard something?" button in the
 * floating-action cluster. The two Slack entry points are specified but deliberately
 * not built in this cut: Slack inbound has no staging signing secret, so a slash
 * command or reaction handler could not be verified before production.
 */
public enum SignalSource {

    /** The Intra capture panel behind the floating "Heard something?" button. */
    INTRA,

    /** The {@code /signal} slash command. Specified (§4.6), not yet built. */
    SLACK_COMMAND,

    /** The 📡 reaction on any Slack message. Specified (§4.6), not yet built. */
    SLACK_REACTION
}
