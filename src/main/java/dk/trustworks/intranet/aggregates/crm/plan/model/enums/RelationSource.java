package dk.trustworks.intranet.aggregates.crm.plan.model.enums;

/**
 * Where the relationship is evidenced from — so a rating can be read against what it is based on.
 *
 * <p>Mirrors the type of the same name in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public enum RelationSource {
    CONTRACT, CALENDAR, SIGNAL, SIGNAL_AND_CALENDAR
}
