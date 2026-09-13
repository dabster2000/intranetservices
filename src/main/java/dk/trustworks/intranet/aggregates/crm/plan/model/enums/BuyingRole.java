package dk.trustworks.intranet.aggregates.crm.plan.model.enums;

/**
 * What a stakeholder does in a buying decision: decides, holds the budget, judges the solution, lives with it, or controls access.
 *
 * <p>Mirrors the type of the same name in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public enum BuyingRole {
    DECISION_MAKER, ECONOMIC_BUYER, TECHNICAL_BUYER, USER_BUYER, GATEKEEPER
}
