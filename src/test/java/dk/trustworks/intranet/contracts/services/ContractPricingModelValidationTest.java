package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.model.PricingModelDefinition;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractPricingModelValidationTest {
    private ContractValidationService service;
    private ContractConsultant consultant;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ContractValidationService();
        service.em = mock(EntityManager.class);
        service.pricingModelRequirementService = mock(PricingModelRequirementService.class);
        TypedQuery<String> projects = mock(TypedQuery.class);
        when(service.em.createQuery(anyString(), eq(String.class))).thenReturn(projects);
        when(projects.setParameter(anyString(), any())).thenReturn(projects);
        when(projects.getResultList()).thenReturn(List.of());
        Query work = mock(Query.class);
        when(service.em.createNativeQuery(anyString())).thenReturn(work);
        when(work.setParameter(anyString(), any())).thenReturn(work);
        when(work.getSingleResult()).thenReturn(new Object[]{0L, 0.0});
        consultant = new ContractConsultant();
        consultant.setContractuuid("contract");
        consultant.setUseruuid("hourly-user");
        consultant.setActiveFrom(LocalDate.of(2020, 1, 1));
        consultant.setActiveTo(LocalDate.of(2020, 2, 1));
        consultant.setRate(600);
    }

    @Test
    void individualHistoricalSaveRequiresModelWhenSalaryOverlaps() {
        when(service.pricingModelRequirementService.isRequired(anyString(), any(), any())).thenReturn(true);
        try (MockedStatic<PricingModelDefinition> models = activeModels()) {
            var report = service.validateContractConsultant(consultant);
            assertFalse(report.isValid());
            assertEquals("pricingModelCode", report.getErrors().getFirst().getField());
            verify(service.pricingModelRequirementService).isRequired("hourly-user",
                    consultant.getActiveFrom(), consultant.getActiveTo());
        }
    }

    @Test
    void unrelatedActivationKeepsExistingMissingModelAndDoesNotReadSalary() {
        Contract contract = new Contract();
        contract.setStatus(ContractStatus.SIGNED);
        contract.setContractConsultants(new HashSet<>(Set.of(consultant)));
        contract.setContractProjects(new HashSet<>());
        try (MockedStatic<PricingModelDefinition> models = activeModels()) {
            assertTrue(service.validateContractActivation(contract).isValid());
            verifyNoInteractions(service.pricingModelRequirementService);
            consultant.setPricingModelCode("INACTIVE");
            assertFalse(service.validateContractActivation(contract).isValid());
        }
    }

    @Test
    void salariedSaveIsOptionalAndActiveFreeOfChargeRemainsSelectable() {
        try (MockedStatic<PricingModelDefinition> models = activeModels()) {
            assertTrue(service.validateContractConsultant(consultant).isValid());
            consultant.setPricingModelCode("COLLEAGUE_HOURS");
            assertTrue(service.validateContractConsultant(consultant).isValid());
        }
    }

    @Test
    void freeOfChargeIsDescriptiveAndPreservesEnteredPositiveRate() {
        consultant.setPricingModelCode("COLLEAGUE_HOURS");
        consultant.setRate(635.32);
        when(service.pricingModelRequirementService.isRequired(anyString(), any(), any())).thenReturn(true);
        try (MockedStatic<PricingModelDefinition> models = activeModels()) {
            assertTrue(service.validateContractConsultant(consultant).isValid());
            assertEquals(635.32, consultant.getRate());
        }
    }

    private static MockedStatic<PricingModelDefinition> activeModels() {
        MockedStatic<PricingModelDefinition> models = mockStatic(PricingModelDefinition.class);
        models.when(PricingModelDefinition::activeCodes)
                .thenReturn(Set.of("FULL_FROM_START", "STEPPED", "PILOT_FREE", "COLLEAGUE_HOURS"));
        return models;
    }
}
