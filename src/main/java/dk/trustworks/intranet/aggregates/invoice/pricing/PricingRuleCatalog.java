// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/PricingRuleCatalog.java
package dk.trustworks.intranet.aggregates.invoice.pricing;


import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalog of pricing rules for contract types.
 *
 * The database ({@code pricing_rule_steps}) is the single source of truth:
 * V98 seeded the former hardcoded legacy rule sets and V397 guarantees their
 * presence, so the old in-code fallback is gone (spec §9.7). If a contract
 * type has no rules that are active on the invoice date, no deductions apply —
 * the only step the system injects is the general-discount fallback in
 * {@link #select(String, LocalDate)}, which prices {@code invoice.discount}
 * and is visible in every breakdown.
 *
 * Rules are read from the database on every call — deliberately uncached
 * (reads are cheap; correctness over micro-performance, spec §9.7).
 */
@JBossLog
@ApplicationScoped
public class PricingRuleCatalog {

    /**
     * Rule id of the system-injected invoice-discount step (source SYSTEM).
     */
    public static final String GENERAL_FALLBACK_RULE_ID = "general-fallback";

    /**
     * Priority of the injected invoice-discount step — late in the pipeline so
     * contract-specific rules always run first.
     */
    public static final int GENERAL_FALLBACK_PRIORITY = 9000;

    /**
     * Select pricing rules for a contract type and date from the database.
     *
     * @param contractTypeCode The contract type code (e.g., "SKI0217_2025")
     * @param invoiceDate The invoice date (for date-based rule filtering)
     * @return RuleSet with applicable rules in deterministic execution order
     *         (priority, then rule id — spec §9.8)
     */
    public RuleSet select(String contractTypeCode, LocalDate invoiceDate) {
        List<RuleStep> base = loadFromDatabaseByCode(contractTypeCode, invoiceDate);
        log.debug("Loaded " + base.size() + " rules from database for " + contractTypeCode);

        // Ensure a GENERAL_DISCOUNT_PERCENT step exists exactly once for ALL contracts
        boolean hasGeneral = base.stream()
                .anyMatch(s -> s.type == RuleStepType.GENERAL_DISCOUNT_PERCENT);
        if (!hasGeneral) {
            List<RuleStep> tmp = new ArrayList<>(base);
            RuleStep fallback = new RuleStep();
            fallback.id = GENERAL_FALLBACK_RULE_ID;
            fallback.label = "Generel rabat";
            fallback.type = RuleStepType.GENERAL_DISCOUNT_PERCENT;
            fallback.base = StepBase.CURRENT_SUM;
            // percent comes from draft.getDiscount(); amount and paramKey unused
            fallback.priority = GENERAL_FALLBACK_PRIORITY;
            tmp.add(fallback);
            base = tmp;
        }

        RuleSet rs = new RuleSet();
        rs.contractTypeCode = contractTypeCode;
        rs.steps = base.stream()
                .filter(s -> s.isActiveOn(invoiceDate))
                .sorted(RuleStep.DETERMINISTIC_ORDER)
                .toList();
        return rs;
    }

    /**
     * Load pricing rules from database for a contract type code.
     * Only rules that are active and date-valid on {@code invoiceDate} are
     * returned, ordered by (priority, id).
     *
     * @param contractTypeCode The contract type code
     * @param invoiceDate The invoice date (for date filtering)
     * @return List of RuleSteps, empty if no rules found
     */
    protected List<RuleStep> loadFromDatabaseByCode(String contractTypeCode, LocalDate invoiceDate) {
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
        step.purpose = entity.getPurpose();
        step.base = entity.getStepBase();
        step.percent = entity.getPercent();
        step.amount = entity.getAmount();
        step.paramKey = entity.getParamKey();
        step.validFrom = entity.getValidFrom();
        step.validTo = entity.getValidTo();
        step.priority = entity.getPriority();
        return step;
    }
}
