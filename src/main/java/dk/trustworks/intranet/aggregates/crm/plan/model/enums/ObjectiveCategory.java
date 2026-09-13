package dk.trustworks.intranet.aggregates.crm.plan.model.enums;

/**
 * What kind of objective this is. Kept apart so a plan of four commercial targets is visibly a plan of four commercial targets.
 *
 * <p>Mirrors the type of the same name in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public enum ObjectiveCategory {
    COMMERCIAL, RELATIONSHIP, CAPABILITY, DELIVERY
}
