package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.financeservice.services.FinanceGlRecoveryImportService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FinanceGlRevenueSourceRebuildHandlerTest {

    @Test
    void delegatesTheExactOwnedBoundsWithoutPublishingRevenueOrCompletingTheWatermark() {
        FinanceGlRecoveryImportService importService = mock(FinanceGlRecoveryImportService.class);
        FinanceGlRevenueSourceRebuildHandler handler = new FinanceGlRevenueSourceRebuildHandler();
        handler.recoveryImportService = importService;
        LocalDate from = LocalDate.parse("2021-07-01");
        LocalDate to = LocalDate.parse("2026-06-30");
        String token = UUID.randomUUID().toString();

        PracticeRevenueSourceRebuildHandler.RebuildResult result = handler.rebuild(
                new PracticeRevenueSourceRebuildHandler.RebuildRequest(from, to, token, null));

        assertEquals(PracticeRevenueSourceRecoveryService.Category.FINANCE_GL, handler.category());
        assertTrue(result.complete());
        verify(importService).rebuild(from, to, token);
    }

    @Test
    void strictImportFailurePropagatesToTheTokenOwningCoordinator() {
        FinanceGlRecoveryImportService importService = mock(FinanceGlRecoveryImportService.class);
        FinanceGlRevenueSourceRebuildHandler handler = new FinanceGlRevenueSourceRebuildHandler();
        handler.recoveryImportService = importService;
        LocalDate from = LocalDate.parse("2021-07-01");
        LocalDate to = LocalDate.parse("2026-06-30");
        String token = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("FINANCE_GL_DRAFT_ENTRIES_FAILED"))
                .when(importService).rebuild(from, to, token);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> handler.rebuild(
                new PracticeRevenueSourceRebuildHandler.RebuildRequest(from, to, token, null)));

        assertEquals("FINANCE_GL_DRAFT_ENTRIES_FAILED", failure.getMessage());
    }
}
