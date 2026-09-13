package dk.trustworks.intranet.aggregates.crm.plan.model.enums;

/**
 * The slot a plan sentence sits in. The slot decides its epistemic kind — nobody picks it — so "we will own it" can never be stored as "we own it".
 *
 * <p>Mirrors the type of the same name in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public enum PlanSlot {
    WHY, CURRENT, DESIRED, QUESTION
}
