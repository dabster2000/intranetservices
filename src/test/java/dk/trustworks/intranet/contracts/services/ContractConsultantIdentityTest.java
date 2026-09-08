package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.ValidationReport;
import dk.trustworks.intranet.contracts.exceptions.ContractValidationException;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractConsultantIdentityTest {
    private ContractService service;
    private ContractConsultant stored;

    @BeforeEach
    void setUp() {
        service = new ContractService();
        service.em = mock(EntityManager.class);
        service.requestHeaderHolder = mock(RequestHeaderHolder.class);
        service.validationService = mock(ContractValidationService.class);
        stored = new ContractConsultant();
        stored.setUuid("assignment");
        stored.setContractuuid("contract");
        stored.setUseruuid("hourly-user");
        when(service.em.find(ContractConsultant.class, "assignment")).thenReturn(stored);
    }

    @Test
    void updateCannotSubstituteSalariedUserBeforeValidation() {
        ContractConsultant input = new ContractConsultant();
        input.setUseruuid("salaried-user");
        assertThrows(BadRequestException.class, () -> service.updateConsultant("contract", "assignment", input));
        verifyNoInteractions(service.validationService);
    }

    @Test
    void wrongContractOrAssignmentIdentityCannotReadSalary() {
        assertThrows(NotFoundException.class, () -> service.updateConsultant("other-contract", "assignment", new ContractConsultant()));
        ContractConsultant input = new ContractConsultant();
        input.setUuid("other-assignment");
        assertThrows(BadRequestException.class, () -> service.updateConsultant("contract", "assignment", input));
        verifyNoInteractions(service.validationService);
    }

    @Test
    void updateFillsIdentityFromStoredAssignmentBeforeCheckingRequiredModel() {
        ContractConsultant input = new ContractConsultant();
        ValidationReport report = new ValidationReport();
        report.addError(new ContractValidationException.ValidationError("pricingModelCode", "Required",
                ContractValidationException.ErrorType.MISSING_REQUIRED));
        when(service.validationService.validateContractConsultant(input)).thenReturn(report);
        doCallRealMethod().when(service.validationService).enforceValidation(report);
        assertThrows(ContractValidationException.class, () -> service.updateConsultant("contract", "assignment", input));
        assertEquals("hourly-user", input.getUseruuid());
        assertEquals("assignment", input.getUuid());
        assertEquals("contract", input.getContractuuid());
    }

    @Test
    void createCannotMismatchPathAndBodyUser() {
        ContractConsultant input = new ContractConsultant();
        input.setContractuuid("contract");
        input.setUseruuid("salaried-user");
        assertThrows(BadRequestException.class, () -> service.addConsultant("contract", "hourly-user", input));
        verifyNoInteractions(service.validationService);
    }
}
