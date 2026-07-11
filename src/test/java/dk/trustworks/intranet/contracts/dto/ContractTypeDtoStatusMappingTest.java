package dk.trustworks.intranet.contracts.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every DTO mapping path (fromEntity / convenience constructor)
 * derives the C1 status field, so list, single, with-rules, and all-rules
 * responses all carry it automatically.
 *
 * Uses far-past/far-future dates so the assertions are stable regardless of
 * the Europe/Copenhagen "today" used at mapping time. Plain JUnit — entities
 * are used as POJOs, no Quarkus boot required.
 */
class ContractTypeDtoStatusMappingTest {

    private static final LocalDate FAR_PAST = LocalDate.of(2000, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2099, 1, 1);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- ContractTypeDefinitionDTO ---

    @Test
    void agreementDto_derivesActiveStatus() {
        ContractTypeDefinition entity = agreement(true, FAR_PAST, FAR_FUTURE);
        assertEquals(LifecycleStatus.ACTIVE, ContractTypeDefinitionDTO.fromEntity(entity).getStatus());
        assertEquals(LifecycleStatus.ACTIVE, new ContractTypeDefinitionDTO(entity).getStatus());
    }

    @Test
    void agreementDto_derivesScheduledStatus() {
        ContractTypeDefinition entity = agreement(true, FAR_FUTURE, null);
        assertEquals(LifecycleStatus.SCHEDULED, ContractTypeDefinitionDTO.fromEntity(entity).getStatus());
    }

    @Test
    void agreementDto_derivesExpiredStatus() {
        ContractTypeDefinition entity = agreement(true, FAR_PAST, FAR_PAST.plusYears(1));
        assertEquals(LifecycleStatus.EXPIRED, ContractTypeDefinitionDTO.fromEntity(entity).getStatus());
    }

    @Test
    void agreementDto_derivesArchivedStatus() {
        ContractTypeDefinition entity = agreement(false, FAR_PAST, FAR_FUTURE);
        assertEquals(LifecycleStatus.ARCHIVED, ContractTypeDefinitionDTO.fromEntity(entity).getStatus());
    }

    @Test
    void agreementDto_countsAreNullUntilListEnrichment() {
        ContractTypeDefinitionDTO dto = ContractTypeDefinitionDTO.fromEntity(agreement(true, null, null));
        assertNull(dto.getContractCount(), "contractCount is a list-endpoint enrichment");
        assertNull(dto.getActivePricingRuleCount(), "activePricingRuleCount is a list-endpoint enrichment");
    }

    @Test
    void agreementDto_statusSerializesAsPlainString() throws Exception {
        ContractTypeDefinitionDTO dto = ContractTypeDefinitionDTO.fromEntity(agreement(false, null, null));
        String json = MAPPER.writeValueAsString(dto.getStatus());
        assertEquals("\"ARCHIVED\"", json);
    }

    // --- PricingRuleStepDTO ---

    @Test
    void pricingRuleDto_derivesActiveStatus() {
        PricingRuleStepDTO dto = PricingRuleStepDTO.fromEntity(pricingRule(true, FAR_PAST, FAR_FUTURE));
        assertEquals(LifecycleStatus.ACTIVE, dto.getStatus());
    }

    @Test
    void pricingRuleDto_derivesScheduledStatus() {
        PricingRuleStepDTO dto = PricingRuleStepDTO.fromEntity(pricingRule(true, FAR_FUTURE, null));
        assertEquals(LifecycleStatus.SCHEDULED, dto.getStatus());
    }

    @Test
    void pricingRuleDto_derivesExpiredStatus() {
        PricingRuleStepDTO dto = PricingRuleStepDTO.fromEntity(pricingRule(true, FAR_PAST, FAR_PAST.plusYears(1)));
        assertEquals(LifecycleStatus.EXPIRED, dto.getStatus());
    }

    @Test
    void pricingRuleDto_derivesDisabledStatus_inactiveBeatsDates() {
        PricingRuleStepDTO dto = PricingRuleStepDTO.fromEntity(pricingRule(false, FAR_PAST, FAR_PAST.plusYears(1)));
        assertEquals(LifecycleStatus.DISABLED, dto.getStatus());
    }

    // --- ValidationRuleDTO ---

    @Test
    void validationRuleDto_derivesActiveStatus() {
        ValidationRuleDTO dto = ValidationRuleDTO.fromEntity(validationRule(true));
        assertEquals(LifecycleStatus.ACTIVE, dto.getStatus());
    }

    @Test
    void validationRuleDto_derivesDisabledStatus() {
        ValidationRuleDTO dto = ValidationRuleDTO.fromEntity(validationRule(false));
        assertEquals(LifecycleStatus.DISABLED, dto.getStatus());
    }

    // --- fixtures ---

    private static ContractTypeDefinition agreement(boolean active, LocalDate validFrom, LocalDate validUntil) {
        ContractTypeDefinition entity = new ContractTypeDefinition();
        entity.setCode("TEST_AGREEMENT");
        entity.setName("Test agreement");
        entity.setActive(active);
        entity.setValidFrom(validFrom);
        entity.setValidUntil(validUntil);
        return entity;
    }

    private static PricingRuleStepEntity pricingRule(boolean active, LocalDate validFrom, LocalDate validTo) {
        PricingRuleStepEntity entity = new PricingRuleStepEntity();
        entity.setContractTypeCode("TEST_AGREEMENT");
        entity.setRuleId("test-rule");
        entity.setLabel("Test rule");
        entity.setRuleStepType(RuleStepType.ADMIN_FEE_PERCENT);
        entity.setStepBase(StepBase.CURRENT_SUM);
        entity.setPercent(BigDecimal.valueOf(5.0));
        entity.setPriority(10);
        entity.setActive(active);
        entity.setValidFrom(validFrom);
        entity.setValidTo(validTo);
        return entity;
    }

    private static ContractValidationRuleEntity validationRule(boolean active) {
        ContractValidationRuleEntity entity = new ContractValidationRuleEntity();
        entity.setContractTypeCode("TEST_AGREEMENT");
        entity.setRuleId("test-validation-rule");
        entity.setLabel("Test validation rule");
        entity.setValidationType(ValidationType.NOTES_REQUIRED);
        entity.setRequired(true);
        entity.setPriority(10);
        entity.setActive(active);
        return entity;
    }
}
