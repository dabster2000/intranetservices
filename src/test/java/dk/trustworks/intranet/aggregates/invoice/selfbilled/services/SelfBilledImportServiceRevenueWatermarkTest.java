package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfBilledImportServiceRevenueWatermarkTest {

    @Test
    void successfulMultiTransactionImportUsesOwnedRunningReadyTransition(){
        PracticeRevenueDirtyMarker marker=mock(PracticeRevenueDirtyMarker.class);
        when(marker.beginImport(PracticeRevenueDirtyMarker.Source.SELF_BILLED)).thenReturn("owner");
        SelfBilledImportService service=new SelfBilledImportService();
        service.practiceRevenueDirtyMarker=marker;

        int result=service.withImportWatermark(LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-02-28"),()->7);

        assertEquals(7,result);
        var order=inOrder(marker);
        order.verify(marker).beginImport(PracticeRevenueDirtyMarker.Source.SELF_BILLED);
        order.verify(marker).completeImport(PracticeRevenueDirtyMarker.Source.SELF_BILLED,"owner",
                java.time.YearMonth.parse("2026-01"),java.time.YearMonth.parse("2026-02"));
    }

    @Test
    void failedImportRetainsPriorVersionAndMovesOwnedAttemptToFailed(){
        PracticeRevenueDirtyMarker marker=mock(PracticeRevenueDirtyMarker.class);
        when(marker.beginImport(PracticeRevenueDirtyMarker.Source.SELF_BILLED)).thenReturn("owner");
        SelfBilledImportService service=new SelfBilledImportService();
        service.practiceRevenueDirtyMarker=marker;

        assertThrows(IllegalStateException.class,()->service.withImportWatermark(
                LocalDate.parse("2026-01-01"),LocalDate.parse("2026-02-28"),
                ()->{throw new IllegalStateException("source failed");}));

        verify(marker).failImport(PracticeRevenueDirtyMarker.Source.SELF_BILLED,"owner");
    }
}
