package dk.trustworks.intranet.aggregates.crm.plan.model.enums;

/**
 * A recurring action's rhythm. An action has a due date OR a cadence; one with neither is incomplete (rule 2).
 *
 * <p>Mirrors the type of the same name in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public enum ActionCadence {
    WEEKLY, MONTHLY, QUARTERLY
}
