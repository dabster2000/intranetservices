package dk.trustworks.intranet.aggregates.crm.plan.model.enums;

/**
 * Whether a plan has been started at all. NONE is the state of every account until somebody writes the first sentence.
 *
 * <p>Mirrors the type of the same name in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public enum PlanStatus {
    NONE, ACTIVE
}
