// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/RuleSet.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

import java.time.LocalDate;
import java.util.List;

/**
 * Set of pricing rules for a contract type.
 * Contains the contract type code and the applicable pricing rule steps.
 */
public class RuleSet {
    /**
     * Contract type code (e.g., "SKI0217_2025" or "PERIOD").
     */
    public String contractTypeCode;

    /**
     * List of pricing rule steps to apply.
     */
    public List<RuleStep> steps;

    /**
     * Get steps that are active on a specific date.
     *
     * @param date The date to filter by
     * @return List of active steps in deterministic execution order
     *         (priority, then rule id — spec §9.8)
     */
    public List<RuleStep> stepsFor(LocalDate date) {
        return steps.stream().filter(s -> s.isActiveOn(date))
                .sorted(RuleStep.DETERMINISTIC_ORDER)
                .toList();
    }
}
