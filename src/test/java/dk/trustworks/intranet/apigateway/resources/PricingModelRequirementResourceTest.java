package dk.trustworks.intranet.apigateway.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.contracts.services.PricingModelRequirementService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingModelRequirementResourceTest {
    @Test
    void scopedEndpointReturnsOnlyRequirementForRequestedDates() throws Exception {
        ContractResource resource = new ContractResource();
        resource.pricingModelRequirementService = mock(PricingModelRequirementService.class);
        when(resource.pricingModelRequirementService.isRequired("user", LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 31))).thenReturn(true);
        var result = resource.pricingModelRequirement("user", "2020-01-01", "2020-01-31");
        assertEquals("{\"required\":true}", new ObjectMapper().writeValueAsString(result));
        var method = ContractResource.class.getMethod("pricingModelRequirement", String.class, String.class, String.class);
        assertArrayEquals(new String[]{"contracts:read"}, method.getAnnotation(RolesAllowed.class).value());
    }

    @Test
    void malformedOrMissingDatesReturnBadRequestBeforeSalaryLookup() {
        ContractResource resource = new ContractResource();
        resource.pricingModelRequirementService = mock(PricingModelRequirementService.class);
        assertThrows(BadRequestException.class, () -> resource.pricingModelRequirement("user", null, "2020-01-31"));
        assertThrows(BadRequestException.class, () -> resource.pricingModelRequirement("user", "2020-02-30", "2020-03-01"));
        verifyNoInteractions(resource.pricingModelRequirementService);
    }

    @Test
    void failedSalaryLookupCannotTurnIntoOptionalResponse() {
        ContractResource resource = new ContractResource();
        resource.pricingModelRequirementService = mock(PricingModelRequirementService.class);
        when(resource.pricingModelRequirementService.isRequired("user", LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 31))).thenThrow(new PersistenceException("offline"));
        assertThrows(PersistenceException.class, () -> resource.pricingModelRequirement("user", "2020-01-01", "2020-01-31"));
    }
}
