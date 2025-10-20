package dk.trustworks.intranet.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Composite DTO that includes a contract type with all its associated rules.
 * Provides a complete view of pricing rules, validation rules, and rate adjustments.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractTypeWithAllRulesDTO {
    private ContractTypeDefinitionDTO contractType;
    private List<PricingRuleStepDTO> pricingRules;
    private List<ValidationRuleDTO> validationRules;
    private List<RateAdjustmentDTO> rateAdjustments;
}
