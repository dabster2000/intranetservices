package dk.trustworks.intranet.aggregates.invoice.pricing;

/**
 * Business purpose tag for {@link RuleStepType#PERCENT_DISCOUNT_ON_SUM} rules
 * (spec §8.1 — Framework Agreements Phase 3, "honest pricing").
 *
 * <p>Money-neutral: the {@link PricingEngine} delta formula {@code -(base × pct/100)}
 * is identical for both purposes and never reads the tag. The engine reads it ONLY
 * for label formatting — {@code ADMIN_FEE} rows render their stored label verbatim
 * (like the legacy {@code ADMIN_FEE_PERCENT} branch), keeping labels byte-identical
 * across the V396 retype (spec §12.2). The tag also drives the user-facing taxonomy
 * ("Percentage deduction · Discount | Admin fee").
 *
 * <p>Persisted as {@code pricing_rule_steps.purpose VARCHAR(20) NULL} (V395);
 * {@code null} on system/placement rows ({@code GENERAL_DISCOUNT_PERCENT},
 * {@code FIXED_DEDUCTION}) and on untagged rows.
 */
public enum RulePurpose {
    /** A genuine customer discount (trapperabat, commercial adjustment, ...). */
    DISCOUNT,
    /** A fee withheld by the framework owner (SKI administrationsgebyr, MSP fee, ...). */
    ADMIN_FEE
}
