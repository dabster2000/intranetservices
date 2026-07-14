package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeStaffingResponseDTO;
import dk.trustworks.intranet.aggregates.practices.services.CxoPracticeOperatingCostService;
import dk.trustworks.intranet.aggregates.practices.services.CxoPracticeStaffingService;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CxoPracticeResourcesContractTest {

    @Test
    void operatingCost_isProtectedAndDefaultsToBooked() {
        assertProtectedPath(CxoPracticeOperatingCostResource.class, "/practices/cxo/operating-cost");
        CxoPracticeOperatingCostService service = mock(CxoPracticeOperatingCostService.class);
        PracticeOperatingCostResponseDTO expected = mock(PracticeOperatingCostResponseDTO.class);
        when(service.getOperatingCost(CostSource.BOOKED)).thenReturn(expected);

        CxoPracticeOperatingCostResource resource = new CxoPracticeOperatingCostResource();
        resource.service = service;

        assertSame(expected, resource.getOperatingCost(null));
        verify(service).getOperatingCost(CostSource.BOOKED);
    }

    @Test
    void operatingCost_acceptsBookedPlusDraft() {
        CxoPracticeOperatingCostService service = mock(CxoPracticeOperatingCostService.class);
        PracticeOperatingCostResponseDTO expected = mock(PracticeOperatingCostResponseDTO.class);
        when(service.getOperatingCost(CostSource.BOOKED_PLUS_DRAFT)).thenReturn(expected);
        CxoPracticeOperatingCostResource resource = new CxoPracticeOperatingCostResource();
        resource.service = service;

        assertSame(expected, resource.getOperatingCost("booked_plus_draft"));
    }

    @Test
    void staffing_isProtectedAndDelegates() {
        assertProtectedPath(CxoPracticeStaffingResource.class, "/finance/cxo/practice-staffing");
        CxoPracticeStaffingService service = mock(CxoPracticeStaffingService.class);
        PracticeStaffingResponseDTO expected = mock(PracticeStaffingResponseDTO.class);
        when(service.getStaffing(null)).thenReturn(expected);
        CxoPracticeStaffingResource resource = new CxoPracticeStaffingResource();
        resource.service = service;

        assertSame(expected, resource.getStaffing(null));
        verify(service).getStaffing(null);
    }

    @Test
    void staffingSelectedPracticeReturnsLazyDetailContract() {
        CxoPracticeStaffingService service = mock(CxoPracticeStaffingService.class);
        PracticeStaffingResponseDTO expected = mock(PracticeStaffingResponseDTO.class);
        when(service.getStaffing("PM")).thenReturn(expected);
        CxoPracticeStaffingResource resource = new CxoPracticeStaffingResource();
        resource.service = service;

        assertSame(expected, resource.getStaffing("PM"));
        verify(service).getStaffing("PM");
    }

    @Test
    void staffingInvalidPracticeIsBadRequest() {
        CxoPracticeStaffingService service = mock(CxoPracticeStaffingService.class);
        doThrow(new IllegalArgumentException("practice must be one of PM, BA, CYB, DEV, SA"))
                .when(service).getStaffing("sales");
        CxoPracticeStaffingResource resource = new CxoPracticeStaffingResource();
        resource.service = service;

        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> resource.getStaffing("sales"));
    }

    private static void assertProtectedPath(Class<?> resourceClass, String expectedPath) {
        Path path = resourceClass.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals(expectedPath, path.value());
        RolesAllowed roles = resourceClass.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertArrayEquals(new String[]{"dashboard:read"}, roles.value());
    }
}
