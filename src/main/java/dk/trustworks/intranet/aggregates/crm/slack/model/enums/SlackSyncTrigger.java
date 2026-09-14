package dk.trustworks.intranet.aggregates.crm.slack.model.enums;

/**
 * What started a Slack sync run (spec §5.4).
 *
 * <p>The distinction is worth storing because only one of the two has a person behind it:
 * {@code started_by} is written for {@link #MANUAL} and left null for {@link #SCHEDULED},
 * and a run that misbehaved is read very differently depending on whether somebody was
 * sitting in front of it at the time.
 *
 * <p>The column is {@code trigger_kind} and not {@code trigger} because {@code TRIGGER} is
 * a reserved word in MariaDB — the same class of mistake {@code LINES} caused in V534.
 */
public enum SlackSyncTrigger {

    /** The nightly job. */
    SCHEDULED,

    /** An admin pressed the button on Settings → CRM. */
    MANUAL
}
