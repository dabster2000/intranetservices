// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/RuleSet.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

import dk.trustworks.intranet.contracts.model.enums.ContractType;

import java.time.LocalDate;
import java.util.List;

public class RuleSet {
    public ContractType contractType;
    public List<RuleStep> steps;

    public List<RuleStep> stepsFor(LocalDate date) {
        return steps.stream().filter(s -> s.isActiveOn(date))
                .sorted((a,b) -> Integer.compare(a.priority, b.priority))
                .toList();
    }
}
