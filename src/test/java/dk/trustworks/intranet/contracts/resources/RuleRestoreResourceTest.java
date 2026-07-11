package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.PricingRuleStepDTO;
import dk.trustworks.intranet.contracts.dto.ValidationRuleDTO;
import dk.trustworks.intranet.contracts.services.ContractValidationRuleService;
import dk.trustworks.intranet.contracts.services.PricingRuleStepService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the restore endpoints (spec §9.3 / C5):
 * POST /api/contract-types/{contractTypeCode}/rules/{ruleId}/activate
 * POST /api/contract-types/{contractTypeCode}/validation-rules/{ruleId}/activate
 * Both must return 200 with the updated rule DTO and surface 404 for unknown rule IDs.
 */
class RuleRestoreResourceTest {

    private static final String CODE = "SKI0217_2025";

    @Test
    void pricingRuleActivate_returns200WithUpdatedDto() {
        PricingRuleResource resource = new PricingRuleResource();
        PricingRuleStepService service = mock(PricingRuleStepService.class);
        resource.pricingRuleService = service;
        resource.requestHeaderHolder = new RequestHeaderHolder();

        PricingRuleStepDTO restored = new PricingRuleStepDTO();
        restored.setRuleId("restore-me");
        restored.setActive(true);
        when(service.activateRule(CODE, "restore-me")).thenReturn(restored);

        Response response = resource.activate(CODE, "restore-me");

        assertEquals(200, response.getStatus());
        assertSame(restored, response.getEntity());
    }

    @Test
    void pricingRuleActivate_unknownRuleId_propagatesNotFound() {
        PricingRuleResource resource = new PricingRuleResource();
        PricingRuleStepService service = mock(PricingRuleStepService.class);
        resource.pricingRuleService = service;
        resource.requestHeaderHolder = new RequestHeaderHolder();

        when(service.activateRule(CODE, "missing-rule"))
                .thenThrow(new NotFoundException("Rule with ID 'missing-rule' not found for contract type '" + CODE + "'"));

        assertThrows(NotFoundException.class, () -> resource.activate(CODE, "missing-rule"));
    }

    @Test
    void validationRuleActivate_returns200WithUpdatedDto() {
        ContractValidationRuleResource resource = new ContractValidationRuleResource();
        ContractValidationRuleService service = mock(ContractValidationRuleService.class);
        resource.validationRuleService = service;
        resource.requestHeaderHolder = new RequestHeaderHolder();

        ValidationRuleDTO restored = new ValidationRuleDTO();
        restored.setRuleId("restore-me");
        restored.setActive(true);
        when(service.activate(CODE, "restore-me")).thenReturn(restored);

        Response response = resource.activate(CODE, "restore-me");

        assertEquals(200, response.getStatus());
        assertSame(restored, response.getEntity());
    }

    @Test
    void validationRuleActivate_unknownRuleId_propagatesNotFound() {
        ContractValidationRuleResource resource = new ContractValidationRuleResource();
        ContractValidationRuleService service = mock(ContractValidationRuleService.class);
        resource.validationRuleService = service;
        resource.requestHeaderHolder = new RequestHeaderHolder();

        when(service.activate(CODE, "missing-rule"))
                .thenThrow(new NotFoundException("Validation rule with ID 'missing-rule' not found for contract type '" + CODE + "'"));

        assertThrows(NotFoundException.class, () -> resource.activate(CODE, "missing-rule"));
    }
}
