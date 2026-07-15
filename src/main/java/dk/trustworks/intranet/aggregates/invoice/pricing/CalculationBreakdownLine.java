// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/CalculationBreakdownLine.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;

public class CalculationBreakdownLine {
    public String ruleId;
    public String label;
    public BigDecimal base;
    public BigDecimal rateOrAmount; // procent i %, eller beløb
    public BigDecimal delta;        // fradrag/ændring (negativt for rabat)
    public BigDecimal cumulative;   // akkumuleret sum efter trinnet

    @JsonIgnore public String pricingPolicyVersion;
    @JsonIgnore public String pricingStepId;
    @JsonIgnore public Integer pricingStepSequence;
    @JsonIgnore public String pricingRuleType;
    @JsonIgnore public String pricingInputFingerprint;
    @JsonIgnore public String pricingOutputFingerprint;
    @JsonIgnore public String calculationAlgorithmVersion;
}
