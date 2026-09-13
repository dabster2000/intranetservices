package dk.trustworks.intranet.aggregates.crm.plan.model.enums;

/**
 * Red/amber/green. Null is a fourth, real state: NOT ASSESSED. A blank must never read as green (account-plan data model, rule 8).
 *
 * <p>Mirrors the type of the same name in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public enum PlanRag {
    GREEN, AMBER, RED
}
