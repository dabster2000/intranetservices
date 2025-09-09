// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/PricingRuleCatalog.java
package dk.trustworks.intranet.aggregates.invoice.pricing;


import dk.trustworks.intranet.contracts.model.enums.ContractType;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@ApplicationScoped
public class PricingRuleCatalog {

    private final Map<ContractType, List<RuleStep>> rulesByType = new EnumMap<>(ContractType.class);

    public PricingRuleCatalog() {
        // --- SKI0217_2021: nøglerabat -> 2% admin -> fast fradrag 2000 -> generel rabat
        rulesByType.put(ContractType.SKI0217_2021, List.of(
                step("ski21721-key", "SKI trapperabat", RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.SUM_BEFORE_DISCOUNTS, null, null, "trapperabat", 10),
                step("ski21721-admin", "2% SKI administrationsgebyr", RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM, bd(2), null, null, 20),
                fixed("ski21721-fee", "Faktureringsgebyr", bd(2000), 30),
                step("ski21721-general", "Generel rabat", RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM, null, null, null, 40)
        ));

        // --- SKI0217_2025: nøglerabat -> 4% admin -> generel rabat
        rulesByType.put(ContractType.SKI0217_2025, List.of(
                step("ski21725-key", "SKI trapperabat", RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.SUM_BEFORE_DISCOUNTS, null, null, "trapperabat", 10),
                step("ski21725-admin", "4% SKI administrationsgebyr", RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM, bd(4), null, null, 20),
                step("ski21725-general", "Generel rabat", RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM, null, null, null, 40)
        ));

        // --- SKI0215_2025: 4% admin -> generel rabat
        rulesByType.put(ContractType.SKI0215_2025, List.of(
                step("ski21525-admin", "4% SKI administrationsgebyr", RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM, bd(4), null, null, 20),
                step("ski21525-general", "Generel rabat", RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM, null, null, null, 40)
        ));
    }

    public RuleSet select(ContractType contractType, LocalDate invoiceDate) {
        List<RuleStep> base = rulesByType.getOrDefault(contractType, List.of());
        RuleSet rs = new RuleSet();
        rs.contractType = contractType;
        rs.steps = base.stream().filter(s -> s.isActiveOn(invoiceDate)).sorted(Comparator.comparingInt(s -> s.priority)).toList();
        return rs;
    }

    private static RuleStep step(String id, String label, RuleStepType type, StepBase base, BigDecimal pct, BigDecimal amt, String paramKey, int prio) {
        RuleStep s = new RuleStep();
        s.id = id; s.label = label; s.type = type; s.base = base; s.percent = pct; s.amount = amt; s.paramKey = paramKey; s.priority = prio;
        return s;
    }
    private static RuleStep fixed(String id, String label, BigDecimal amt, int prio) {
        return step(id, label, RuleStepType.FIXED_DEDUCTION, StepBase.CURRENT_SUM, null, amt, null, prio);
    }
    private static BigDecimal bd(double d) { return BigDecimal.valueOf(d); }
}
