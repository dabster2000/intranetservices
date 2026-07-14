package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.services.CxoFinanceService;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CxoFinanceResourcePracticeHistoryTest {

    @Test
    void rejectsReversedAndOverlongRanges() {
        CxoFinanceResource resource = resource();

        assertThrows(BadRequestException.class, () -> resource.getPracticeUtilizationHistory(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 30), null, null));
        assertThrows(BadRequestException.class, () -> resource.getPracticeUtilizationHistory(
                LocalDate.of(2022, 1, 1), LocalDate.of(2026, 1, 2), null, null));
    }

    @Test
    void acceptsForwardRangeAtBoundary() {
        CxoFinanceResource resource = resource();

        assertDoesNotThrow(() -> resource.getPracticeUtilizationHistory(
                LocalDate.of(2023, 1, 15), LocalDate.of(2026, 1, 1), "PM", null));
    }

    private static CxoFinanceResource resource() {
        CxoFinanceService service = mock(CxoFinanceService.class);
        when(service.getPracticeUtilizationHistory(any(), any(), any(), any())).thenReturn(List.of());
        CxoFinanceResource resource = new CxoFinanceResource();
        resource.cxoFinanceService = service;
        return resource;
    }
}
