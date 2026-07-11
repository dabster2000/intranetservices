package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.ContractRuleSetDTO;
import dk.trustworks.intranet.contracts.services.RuleResolutionService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractRuleOverrideTimesheetResourceTest {

    @Test
    void exposesExactUngatedReadPathAndReturnsResolvedRuleSet() throws Exception {
        String contractUuid = "11111111-1111-4111-8111-111111111111";
        LocalDate date = LocalDate.of(2026, 7, 11);
        ContractRuleSetDTO ruleSet = ContractRuleSetDTO.builder()
            .contractUuid(contractUuid)
            .contractFound(true)
            .contractTypeCode("DAGROFA_2026")
            .agreementName("Dagrofa 2026")
            .effectiveDate(date)
            .build();
        RuleResolutionService service = mock(RuleResolutionService.class);
        when(service.getTimesheetEffectiveRuleSet(contractUuid, date)).thenReturn(ruleSet);
        ContractRuleOverrideResource resource = new ContractRuleOverrideResource();
        resource.resolutionService = service;

        var response = resource.getTimesheetEffectiveRules(contractUuid, date);

        assertEquals(200, response.getStatus());
        assertSame(ruleSet, response.getEntity());
        verify(service).getTimesheetEffectiveRuleSet(contractUuid, date);

        var method = ContractRuleOverrideResource.class.getMethod(
            "getTimesheetEffectiveRules",
            String.class,
            LocalDate.class
        );
        assertEquals("/timesheet-effective", method.getAnnotation(Path.class).value());
        assertArrayEquals(
            new String[]{"contracts:read"},
            method.getAnnotation(RolesAllowed.class).value()
        );
    }

    @Test
    void missingHistoricalContractIsReturnedAsExplicitEmpty200() {
        RuleResolutionService service = mock(RuleResolutionService.class);
        ContractRuleSetDTO missing = ContractRuleSetDTO.builder()
            .contractUuid("missing")
            .contractFound(false)
            .effectiveDate(LocalDate.of(2026, 7, 11))
            .build();
        when(service.getTimesheetEffectiveRuleSet("missing", LocalDate.of(2026, 7, 11)))
            .thenReturn(missing);
        ContractRuleOverrideResource resource = new ContractRuleOverrideResource();
        resource.resolutionService = service;

        var response = resource.getTimesheetEffectiveRules(
            "missing",
            LocalDate.of(2026, 7, 11)
        );

        assertEquals(200, response.getStatus());
        assertSame(missing, response.getEntity());
    }
}
