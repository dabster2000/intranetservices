package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.dto.PricingRuleStepDTO;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PricingRuleStepService#activateRule(String, String)} (restore endpoint, spec §9.3 / C5).
 * Panache statics are mocked so no database is required; the DB-backed round trip lives in
 * {@link ContractRuleRestoreRoundTripTest}.
 */
class PricingRuleStepServiceActivateTest {

    private static final String CODE = "SKI0217_2025";

    private final PricingRuleStepService service = new PricingRuleStepService();

    @Test
    void activateRule_unknownRuleId_throwsNotFound() {
        try (MockedStatic<PricingRuleStepEntity> statics = mockStatic(PricingRuleStepEntity.class)) {
            statics.when(() -> PricingRuleStepEntity.findByContractTypeAndRuleId(CODE, "missing-rule"))
                    .thenReturn(null);

            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> service.activateRule(CODE, "missing-rule"));
            assertTrue(ex.getMessage().contains("missing-rule"));
            assertTrue(ex.getMessage().contains(CODE));
        }
    }

    @Test
    void activateRule_inactiveRule_isRestoredAndReturned() {
        PricingRuleStepEntity entity = spy(newRule("restore-me"));
        entity.setActive(false);
        doNothing().when(entity).persist();

        try (MockedStatic<PricingRuleStepEntity> statics = mockStatic(PricingRuleStepEntity.class)) {
            statics.when(() -> PricingRuleStepEntity.findByContractTypeAndRuleId(CODE, "restore-me"))
                    .thenReturn(entity);

            PricingRuleStepDTO dto = service.activateRule(CODE, "restore-me");

            assertTrue(entity.isActive(), "entity must be re-activated");
            assertTrue(dto.isActive(), "returned DTO must reflect active=true");
            assertEquals("restore-me", dto.getRuleId());
            assertEquals(CODE, dto.getContractTypeCode());
            verify(entity).persist();
        }
    }

    @Test
    void activateRule_alreadyActive_isIdempotent() {
        PricingRuleStepEntity entity = spy(newRule("already-active"));
        doNothing().when(entity).persist();

        try (MockedStatic<PricingRuleStepEntity> statics = mockStatic(PricingRuleStepEntity.class)) {
            statics.when(() -> PricingRuleStepEntity.findByContractTypeAndRuleId(CODE, "already-active"))
                    .thenReturn(entity);

            PricingRuleStepDTO first = service.activateRule(CODE, "already-active");
            PricingRuleStepDTO second = service.activateRule(CODE, "already-active");

            assertTrue(first.isActive());
            assertTrue(second.isActive());
            assertTrue(entity.isActive());
            verify(entity, times(2)).persist();
        }
    }

    private PricingRuleStepEntity newRule(String ruleId) {
        PricingRuleStepEntity entity = new PricingRuleStepEntity();
        entity.setId(42);
        entity.setContractTypeCode(CODE);
        entity.setRuleId(ruleId);
        entity.setLabel("5% SKI administrationsgebyr");
        entity.setRuleStepType(RuleStepType.ADMIN_FEE_PERCENT);
        entity.setStepBase(StepBase.CURRENT_SUM);
        entity.setPercent(new BigDecimal("5.00"));
        entity.setPriority(20);
        entity.setActive(true);
        return entity;
    }
}
