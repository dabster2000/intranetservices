package dk.trustworks.intranet.contracts.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Response DTO containing a contract type and all its pricing rules.
 * Useful for returning complete contract type configuration in a single response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractTypeWithRulesDTO {

    private ContractTypeDefinitionDTO contractType;
    private List<PricingRuleStepDTO> rules;
    private int totalRules;
    private int activeRules;

    public ContractTypeWithRulesDTO(ContractTypeDefinitionDTO contractType, List<PricingRuleStepDTO> rules) {
        this.contractType = contractType;
        this.rules = rules;
        this.totalRules = rules.size();
        this.activeRules = (int) rules.stream().filter(PricingRuleStepDTO::isActive).count();
    }
}
