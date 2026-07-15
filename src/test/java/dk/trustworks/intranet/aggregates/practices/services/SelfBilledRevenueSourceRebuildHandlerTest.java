package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledImportService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfBilledRevenueSourceRebuildHandlerTest {
    @Test
    void delegatesOnlyTheClosedBoundedSelfBilledRebuild() {
        SelfBilledImportService importService=mock(SelfBilledImportService.class);
        LocalDate from=LocalDate.parse("2021-08-01");
        LocalDate to=LocalDate.parse("2026-07-15");
        when(importService.captureForRecovery(from,to)).thenReturn(Map.of("source",3));
        SelfBilledRevenueSourceRebuildHandler handler=new SelfBilledRevenueSourceRebuildHandler();
        handler.importService=importService;

        PracticeRevenueSourceRebuildHandler.RebuildResult result=handler.rebuild(
                new PracticeRevenueSourceRebuildHandler.RebuildRequest(from,to,
                        "11111111-1111-1111-1111-111111111111",null));

        assertEquals(PracticeRevenueSourceRecoveryService.Category.SELF_BILLED,handler.category());
        assertTrue(result.complete());
        verify(importService).captureForRecovery(from,to);
    }
}
