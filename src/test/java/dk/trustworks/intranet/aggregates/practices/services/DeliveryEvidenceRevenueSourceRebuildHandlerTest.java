package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryEvidenceRevenueSourceRebuildHandlerTest {
    @Test
    void delegatesOwnedRecheckAndReturnsItsObservedCursor(){
        PracticeDeliveryEvidenceRecoveryService service=mock(PracticeDeliveryEvidenceRecoveryService.class);
        var request=new PracticeRevenueSourceRebuildHandler.RebuildRequest(LocalDate.of(2021,7,1),
                LocalDate.of(2026,6,30),"11111111-1111-1111-1111-111111111111",BigInteger.TEN);
        when(service.rebuild(request)).thenReturn(new PracticeDeliveryEvidenceRecoveryService.RecheckResult(
                BigInteger.valueOf(12),3,7,5,2,1,"fingerprint"));
        DeliveryEvidenceRevenueSourceRebuildHandler handler=new DeliveryEvidenceRevenueSourceRebuildHandler();
        handler.recoveryService=service;

        var result=handler.rebuild(request);

        assertEquals(PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE,handler.category());
        assertEquals(BigInteger.valueOf(12),result.observedFactChangeLogId());
        verify(service).rebuild(request);
    }
}
