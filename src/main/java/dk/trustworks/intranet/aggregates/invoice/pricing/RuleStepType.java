// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/RuleStepType.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

public enum RuleStepType {
    /** "Nøglerabat"/trapperabat mm. — percentage deduction; pct resolved paramKey → percent → 0. */
    PERCENT_DISCOUNT_ON_SUM,

    /**
     * Administrationsgebyr i %.
     *
     * @deprecated Retired from creation in Phase 3 (spec §8.1): its engine math is identical to
     * {@link #PERCENT_DISCOUNT_ON_SUM} ({@code delta = -(base × pct/100)}, PricingEngine lines
     * 53-62), so V396 retypes all rows to {@code PERCENT_DISCOUNT_ON_SUM} with
     * {@code purpose = ADMIN_FEE}. The constant stays so rows still in flight during the
     * two-step rollout remain parseable and executable; {@code PricingRuleStepService}
     * rejects it on create/update with 400.
     */
    @Deprecated
    ADMIN_FEE_PERCENT,

    /** Fast fradrag (negativt beløb). */
    FIXED_DEDUCTION,

    /** Generel rabat % (fra invoice.discount) — positions the invoice-level discount in the pipeline. */
    GENERAL_DISCOUNT_PERCENT,

    /**
     * Afrunding til fx 0.01.
     *
     * @deprecated Never offered for creation (spec §8.1): the implementation is a broken
     * placeholder — {@code current.setScale(0, RoundingMode.UNNECESSARY)} (PricingEngine
     * lines 76-80) throws {@code ArithmeticException} on any fractional running total.
     * 0 production rows exist; {@code PricingRuleStepService} rejects it on create/update
     * with 400. The constant stays for old-data safety only.
     */
    @Deprecated
    ROUNDING
}
