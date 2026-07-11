// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/RuleStep.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;

public class RuleStep {

    /**
     * Deterministic execution order (spec §9.8): priority first, then rule id
     * as a stable tie-break. SQL reads already order by (priority, numeric id)
     * — see {@code PricingRuleStepEntity.findByContractTypeAndDate} — and
     * priorities are unique per contract type in practice (enforced at the
     * application level), so the tie-break only guards against hypothetical
     * duplicates.
     */
    public static final Comparator<RuleStep> DETERMINISTIC_ORDER =
            Comparator.<RuleStep>comparingInt(s -> s.priority)
                    .thenComparing(s -> s.id, Comparator.nullsLast(Comparator.naturalOrder()));

    public String id;            // stabil id til logging/breakdown
    public String label;         // visningsnavn (fx "SKI trapperabat")
    public RuleStepType type;
    /**
     * Business purpose tag (V395 column). The engine's delta math NEVER reads it;
     * it is read ONLY for label formatting: {@code ADMIN_FEE} percentage deductions
     * render their stored label verbatim (exactly like the legacy
     * {@code ADMIN_FEE_PERCENT} branch did), keeping breakdown/synthetic-line labels
     * byte-identical across the V396 retype (spec §12.2).
     */
    public RulePurpose purpose;
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
