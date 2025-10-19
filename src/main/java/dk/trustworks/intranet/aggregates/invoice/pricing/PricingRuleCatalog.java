// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/PricingRuleCatalog.java
package dk.trustworks.intranet.aggregates.invoice.pricing;


import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Catalog of pricing rules for contract types.
 * Supports both hardcoded enum-based rules and dynamic database-loaded rules.
 *
 * Loading strategy:
 * 1. First, try to load rules from database by contract type code
 * 2. If no database rules exist, fall back to hardcoded enum-based rules
 * 3. Database rules are cached for performance
 */
@JBossLog
@ApplicationScoped
public class PricingRuleCatalog {

    private final Map<ContractType, List<RuleStep>> rulesByType = new EnumMap<>(ContractType.class);

    public PricingRuleCatalog() {
        // --- HARDCODED RULES FOR BACKWARD COMPATIBILITY ---
        // These remain as fallback for existing enum-based contract types

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

    /**
     * Select pricing rules for a contract type and date.
     * Tries database first, then falls back to hardcoded rules.
     *
     * @param contractTypeCode The contract type code (e.g., "SKI0217_2025")
     * @param invoiceDate The invoice date (for date-based rule filtering)
     * @return RuleSet with applicable rules sorted by priority
     */
    public RuleSet select(String contractTypeCode, LocalDate invoiceDate) {
        // Try loading from database first
        List<RuleStep> base = loadFromDatabaseByCode(contractTypeCode, invoiceDate);

        // If no database rules exist, try hardcoded enum rules for backward compatibility
        if (base.isEmpty()) {
            log.debug("No database rules found for " + contractTypeCode + ", trying hardcoded enum rules");
            try {
                ContractType enumType = ContractType.valueOf(contractTypeCode);
                base = rulesByType.getOrDefault(enumType, List.of());
                if (!base.isEmpty()) {
                    log.debug("Using hardcoded enum rules for " + contractTypeCode);
                }
            } catch (IllegalArgumentException e) {
                log.debug("Contract type " + contractTypeCode + " is not a legacy enum value");
            }
        } else {
            log.debug("Loaded " + base.size() + " rules from database for " + contractTypeCode);
        }

        // Ensure a GENERAL_DISCOUNT_PERCENT step exists exactly once for ALL contracts
        boolean hasGeneral = base.stream()
                .anyMatch(s -> s.type == RuleStepType.GENERAL_DISCOUNT_PERCENT);
        if (!hasGeneral) {
            List<RuleStep> tmp = new ArrayList<>(base);
            // Priority late in the pipeline so specific contract rules run first
            tmp.add(step(
                    "general-fallback",
                    "Generel rabat",
                    RuleStepType.GENERAL_DISCOUNT_PERCENT,
                    StepBase.CURRENT_SUM,
                    null,   // percent comes from draft.getDiscount()
                    null,   // amount unused for this rule type
                    null,   // no param key, uses draft.getDiscount()
                    9000    // low precedence number = runs late
            ));
            base = tmp;
        }

        RuleSet rs = new RuleSet();
        rs.contractTypeCode = contractTypeCode;
        rs.steps = base.stream()
                .filter(s -> s.isActiveOn(invoiceDate))
                .sorted(Comparator.comparingInt(s -> s.priority))
                .toList();
        return rs;
    }

    /**
     * Load pricing rules from database for a contract type code.
     * Results are cached for performance.
     *
     * @param contractTypeCode The contract type code
     * @param invoiceDate The invoice date (for date filtering)
     * @return List of RuleSteps, empty if no rules found
     */
    private List<RuleStep> loadFromDatabaseByCode(String contractTypeCode, LocalDate invoiceDate) {
        log.debug("Loading pricing rules from database for contract type: " + contractTypeCode);

        // Load entities from database
        List<PricingRuleStepEntity> entities =
            PricingRuleStepEntity.findByContractTypeAndDate(contractTypeCode, invoiceDate);

        if (entities.isEmpty()) {
            return List.of();
        }

        // Convert entities to RuleSteps
        List<RuleStep> steps = new ArrayList<>();
        for (PricingRuleStepEntity entity : entities) {
            RuleStep step = convertEntityToRuleStep(entity);
            steps.add(step);
        }

        log.info("Loaded " + steps.size() + " pricing rules from database for " + contractTypeCode);
        return steps;
    }

    /**
     * Convert a database entity to a RuleStep DTO.
     */
    private RuleStep convertEntityToRuleStep(PricingRuleStepEntity entity) {
        RuleStep step = new RuleStep();
        step.id = entity.getRuleId();
        step.label = entity.getLabel();
        step.type = entity.getRuleStepType();
        step.base = entity.getStepBase();
        step.percent = entity.getPercent();
        step.amount = entity.getAmount();
        step.paramKey = entity.getParamKey();
        step.validFrom = entity.getValidFrom();
        step.validTo = entity.getValidTo();
        step.priority = entity.getPriority();
        return step;
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
