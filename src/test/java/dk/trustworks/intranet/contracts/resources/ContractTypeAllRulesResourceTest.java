package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.ContractTypeDefinitionDTO;
import dk.trustworks.intranet.contracts.dto.ContractTypeWithAllRulesDTO;
import dk.trustworks.intranet.contracts.dto.PricingRuleStepDTO;
import dk.trustworks.intranet.contracts.dto.ValidationRuleDTO;
import dk.trustworks.intranet.contracts.services.ContractRateAdjustmentService;
import dk.trustworks.intranet.contracts.services.ContractTypeDefinitionService;
import dk.trustworks.intranet.contracts.services.ContractValidationRuleService;
import dk.trustworks.intranet.contracts.services.PricingRuleStepService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContractTypeResource#getAllRules(String)} (spec §9.3 / C6):
 * the all-rules payload must include INACTIVE validation rules (parity with pricing rules),
 * while rate adjustments remain active-only.
 */
class ContractTypeAllRulesResourceTest {

    private static final String CODE = "SKI0217_2025";

    private ContractTypeResource resource;
    private ContractTypeDefinitionService contractTypeService;
    private PricingRuleStepService pricingRuleService;
    private ContractValidationRuleService validationRuleService;
    private ContractRateAdjustmentService rateAdjustmentService;

    @BeforeEach
    void setUp() {
        resource = new ContractTypeResource();
        contractTypeService = mock(ContractTypeDefinitionService.class);
        pricingRuleService = mock(PricingRuleStepService.class);
        validationRuleService = mock(ContractValidationRuleService.class);
        rateAdjustmentService = mock(ContractRateAdjustmentService.class);
        resource.contractTypeService = contractTypeService;
        resource.pricingRuleService = pricingRuleService;
        resource.validationRuleService = validationRuleService;
        resource.rateAdjustmentService = rateAdjustmentService;
        resource.requestHeaderHolder = new RequestHeaderHolder();
    }

    @Test
    void getAllRules_includesInactiveValidationRules() {
        ContractTypeDefinitionDTO contractType = new ContractTypeDefinitionDTO();
        contractType.setCode(CODE);
        when(contractTypeService.findByCode(CODE)).thenReturn(contractType);

        PricingRuleStepDTO inactivePricingRule = new PricingRuleStepDTO();
        inactivePricingRule.setRuleId("inactive-pricing");
        inactivePricingRule.setActive(false);
        when(pricingRuleService.getRulesForContractType(CODE, true)).thenReturn(List.of(inactivePricingRule));

        ValidationRuleDTO activeValidationRule = new ValidationRuleDTO();
        activeValidationRule.setRuleId("active-validation");
        activeValidationRule.setActive(true);
        ValidationRuleDTO inactiveValidationRule = new ValidationRuleDTO();
        inactiveValidationRule.setRuleId("inactive-validation");
        inactiveValidationRule.setActive(false);
        when(validationRuleService.listAll(CODE, true)).thenReturn(List.of(activeValidationRule, inactiveValidationRule));

        when(rateAdjustmentService.listAll(CODE, false)).thenReturn(List.of());

        Response response = resource.getAllRules(CODE);

        assertEquals(200, response.getStatus());
        ContractTypeWithAllRulesDTO body = (ContractTypeWithAllRulesDTO) response.getEntity();
        assertEquals(CODE, body.getContractType().getCode());

        // C6: validation rules are fetched WITH inactive entries and surface in the payload
        verify(validationRuleService).listAll(CODE, true);
        assertTrue(body.getValidationRules().stream()
                        .anyMatch(r -> "inactive-validation".equals(r.getRuleId()) && !r.isActive()),
                "all-rules must include inactive validation rules");
        assertTrue(body.getValidationRules().stream()
                        .anyMatch(r -> "active-validation".equals(r.getRuleId()) && r.isActive()),
                "all-rules must still include active validation rules");

        // Pricing rules keep including inactive entries (pre-existing behavior)
        verify(pricingRuleService).getRulesForContractType(CODE, true);
        assertTrue(body.getPricingRules().stream()
                .anyMatch(r -> "inactive-pricing".equals(r.getRuleId()) && !r.isActive()));

        // Rate adjustments stay active-only (unchanged per spec §9.3)
        verify(rateAdjustmentService).listAll(CODE, false);
    }
}
