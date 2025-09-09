// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/RuleStepType.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

public enum RuleStepType {
    PERCENT_DISCOUNT_ON_SUM,  // "Nøglerabat"/trapperabat mm.
    ADMIN_FEE_PERCENT,        // Administrationsgebyr i %
    FIXED_DEDUCTION,          // Fast fradrag (negativt beløb)
    GENERAL_DISCOUNT_PERCENT, // Generel rabat % (fra invoice.discount)
    ROUNDING                  // Afrunding til fx 0.01
}
