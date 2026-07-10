package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.ValidationRuleDTO;
import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ContractValidationRuleService#activate(String, String)} (restore endpoint, spec §9.3 / C5).
 * Panache statics are mocked so no database is required; the DB-backed round trip lives in
 * {@link ContractRuleRestoreRoundTripTest}.
 */
class ContractValidationRuleServiceActivateTest {

    private static final String CODE = "SKI0217_2025";

    private final ContractValidationRuleService service = new ContractValidationRuleService();

    @Test
    void activate_unknownRuleId_throwsNotFound() {
        try (MockedStatic<ContractValidationRuleEntity> statics = mockStatic(ContractValidationRuleEntity.class)) {
            statics.when(() -> ContractValidationRuleEntity.findByContractTypeAndRuleId(CODE, "missing-rule"))
                    .thenReturn(null);

            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> service.activate(CODE, "missing-rule"));
            assertTrue(ex.getMessage().contains("missing-rule"));
            assertTrue(ex.getMessage().contains(CODE));
        }
    }

    @Test
    void activate_inactiveRule_isRestoredAndReturned() {
        ContractValidationRuleEntity entity = spy(newRule("restore-me"));
        entity.setActive(false);
        doNothing().when(entity).persist();

        try (MockedStatic<ContractValidationRuleEntity> statics = mockStatic(ContractValidationRuleEntity.class)) {
            statics.when(() -> ContractValidationRuleEntity.findByContractTypeAndRuleId(CODE, "restore-me"))
                    .thenReturn(entity);

            ValidationRuleDTO dto = service.activate(CODE, "restore-me");

            assertTrue(entity.isActive(), "entity must be re-activated");
            assertTrue(dto.isActive(), "returned DTO must reflect active=true");
            assertEquals("restore-me", dto.getRuleId());
            assertEquals(CODE, dto.getContractTypeCode());
            verify(entity).persist();
        }
    }

    @Test
    void activate_alreadyActive_isIdempotent() {
        ContractValidationRuleEntity entity = spy(newRule("already-active"));
        doNothing().when(entity).persist();

        try (MockedStatic<ContractValidationRuleEntity> statics = mockStatic(ContractValidationRuleEntity.class)) {
            statics.when(() -> ContractValidationRuleEntity.findByContractTypeAndRuleId(CODE, "already-active"))
                    .thenReturn(entity);

            ValidationRuleDTO first = service.activate(CODE, "already-active");
            ValidationRuleDTO second = service.activate(CODE, "already-active");

            assertTrue(first.isActive());
            assertTrue(second.isActive());
            assertTrue(entity.isActive());
            verify(entity, times(2)).persist();
        }
    }

    private ContractValidationRuleEntity newRule(String ruleId) {
        ContractValidationRuleEntity entity = new ContractValidationRuleEntity();
        entity.setId(7);
        entity.setContractTypeCode(CODE);
        entity.setRuleId(ruleId);
        entity.setLabel("Notes required for time registration");
        entity.setValidationType(ValidationType.NOTES_REQUIRED);
        entity.setRequired(true);
        entity.setPriority(10);
        entity.setActive(true);
        return entity;
    }
}
