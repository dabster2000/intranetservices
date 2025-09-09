// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/RuleStep.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

public class RuleStep {
    public String id;            // stabil id til logging/breakdown
    public String label;         // visningsnavn (fx "SKI trapperabat")
    public RuleStepType type;
    public StepBase base = StepBase.CURRENT_SUM;
    public BigDecimal percent;   // hvis procentregel
    public BigDecimal amount;    // hvis fast beløb (positiv => fradrag = -amount)
    public String paramKey;      // fx "trapperabat" for kontraktens item
    public LocalDate validFrom;  // inkl.
    public LocalDate validTo;    // ekskl. (null = uendelig)
    public int priority = 100;   // sekvensorden

    public boolean isActiveOn(LocalDate d) {
        boolean after = validFrom == null || !d.isBefore(validFrom);
        boolean before = validTo == null || d.isBefore(validTo);
        return after && before;
    }
}
