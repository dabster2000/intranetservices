package dk.trustworks.intranet.aggregates.crm.plan.model.enums;

/**
 * How an objective is measured. RATE and CONSULTANTS read their current value live from Intra, so the number is set once and never retyped; NUMBER is a figure only a person can update; TEXT has no number at all — only a colour and the reason behind it.
 *
 * <p>Mirrors the type of the same name in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public enum MeasureKind {
    RATE, CONSULTANTS, NUMBER, TEXT
}
