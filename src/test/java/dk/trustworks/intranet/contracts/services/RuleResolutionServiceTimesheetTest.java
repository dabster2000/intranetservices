package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.ValidationOverrideDTO;
import dk.trustworks.intranet.contracts.mappers.ContractOverrideMapper;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleResolutionServiceTimesheetTest {

    @Test
    void resolvesValidationRulesDirectlyWithAuthoritativeAgreementMetadata() {
        String contractUuid = "11111111-1111-4111-8111-111111111111";
        LocalDate date = LocalDate.of(2026, 7, 11);
        Contract contract = new Contract();
        contract.setUuid(contractUuid);
        contract.setContractType("DAGROFA_2026");
        ContractTypeDefinition definition = new ContractTypeDefinition();
        definition.setCode("DAGROFA_2026");
        definition.setName("Dagrofa 2026");
        ContractValidationRuleEntity rule = new ContractValidationRuleEntity();
        rule.setRuleId("notes-required");
        rule.setValidationType(ValidationType.NOTES_REQUIRED);
        ValidationOverrideDTO mappedRule = ValidationOverrideDTO.builder()
            .ruleId("notes-required")
            .validationType(ValidationType.NOTES_REQUIRED)
            .required(true)
            .build();

        RuleResolutionService service = spy(new RuleResolutionService());
        service.mapper = mock(ContractOverrideMapper.class);
        doReturn(contract).when(service).findContract(contractUuid);
        doReturn(definition).when(service).findContractTypeDefinition("DAGROFA_2026");
        doReturn(List.of(rule)).when(service)
            .getEffectiveValidationRules("DAGROFA_2026", contractUuid, date);
        when(service.mapper.fromValidationEntities(List.of(rule)))
            .thenReturn(List.of(mappedRule));

        var result = service.getTimesheetEffectiveRuleSet(contractUuid, date);

        assertEquals(contractUuid, result.getContractUuid());
        assertEquals(Boolean.TRUE, result.getContractFound());
        assertEquals("DAGROFA_2026", result.getContractTypeCode());
        assertEquals("Dagrofa 2026", result.getAgreementName());
        assertEquals(date, result.getEffectiveDate());
        assertEquals(List.of(mappedRule), result.getEffectiveValidationRules());
        assertFalse(result.isFromCache());
        verify(service).getEffectiveValidationRules("DAGROFA_2026", contractUuid, date);
    }

    @Test
    void returnsExplicitEmptyResultBeforeResolvingRulesWhenContractIsMissing() {
        RuleResolutionService service = spy(new RuleResolutionService());
        service.mapper = mock(ContractOverrideMapper.class);
        doReturn(null).when(service).findContract("missing");

        var result = service.getTimesheetEffectiveRuleSet(
            "missing",
            LocalDate.of(2026, 7, 11)
        );

        assertEquals("missing", result.getContractUuid());
        assertEquals(Boolean.FALSE, result.getContractFound());
        assertEquals(LocalDate.of(2026, 7, 11), result.getEffectiveDate());
        assertEquals(List.of(), result.getEffectiveValidationRules());
        assertFalse(result.isFromCache());
        verify(service, never()).getEffectiveValidationRules(anyString(), anyString(), any());
    }
}
