package dk.trustworks.intranet.aggregates.crm.retention.model.enums;

/**
 * What started a CRM retention purge run (spec §7).
 *
 * <p>Worth storing because only one of the two has a person behind it: {@code started_by} is
 * written for {@link #MANUAL} and left null for {@link #SCHEDULED}. For an irreversible
 * sweep that distinction is not cosmetic — "who asked for the run that erased this account"
 * is the first question anybody will have, and a placeholder in that column would make it
 * unanswerable.
 *
 * <p>The column is {@code trigger_kind} and not {@code trigger}: {@code TRIGGER} is a
 * reserved word in MariaDB, the same class of mistake {@code LINES} caused in V534.
 */
public enum CrmPurgeTrigger {

    /** The nightly job at 03:30. */
    SCHEDULED,

    /** An engineer with an admin token called {@code POST /crm/retention/purge}. */
    MANUAL
}
