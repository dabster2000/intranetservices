package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.services.PhantomAttributionService;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PhantomAttributionRevenueSourceRebuildHandlerTest {
    @Test
    void delegatesTheClosedRangeWithoutStartingAnOrdinaryImport(){
        PhantomAttributionService service=mock(PhantomAttributionService.class);
        PhantomAttributionRevenueSourceRebuildHandler handler=
                new PhantomAttributionRevenueSourceRebuildHandler();
        handler.attributionService=service;
        LocalDate from=LocalDate.parse("2021-07-01");
        LocalDate to=LocalDate.parse("2026-06-30");
        var request=new PracticeRevenueSourceRebuildHandler.RebuildRequest(from,to,
                "11111111-1111-1111-1111-111111111111",null);

        var result=handler.rebuild(request);

        assertTrue(result.complete());
        verify(service).deriveRangeStrict(from,to);
    }
}
