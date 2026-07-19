// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/CalculationBreakdownLine.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

import java.math.BigDecimal;

public class CalculationBreakdownLine {
    public String ruleId;
    public String label;
    public BigDecimal base;
    public BigDecimal rateOrAmount; // procent i %, eller beløb
    public BigDecimal delta;        // fradrag/ændring (negativt for rabat)
    public BigDecimal cumulative;   // akkumuleret sum efter trinnet
}
