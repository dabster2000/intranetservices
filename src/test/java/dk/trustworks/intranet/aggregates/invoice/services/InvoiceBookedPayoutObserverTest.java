package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.dao.workservice.services.WorkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * The AFTER_SUCCESS payout observer must (1) forward the event's contract/project/period to
 * WorkService.registerAsPaidout, and (2) swallow any failure — the booking has already
 * committed, so a lock timeout here must never propagate (durability fix).
 */
@ExtendWith(MockitoExtension.class)
class InvoiceBookedPayoutObserverTest {

    @InjectMocks
    InvoiceBookedPayoutObserver observer;

    @Mock
    WorkService workService;

    @Test
    void marks_work_paid_out_with_the_events_contract_project_period() {
        observer.onInvoiceBooked(new InvoiceBookedEvent("i1", "c1", "p1", 6, 2026));
        verify(workService).registerAsPaidout("c1", "p1", 6, 2026);
    }

    @Test
    void swallows_payout_failure_so_booking_stays_durable() {
        doThrow(new RuntimeException("Lock wait timeout exceeded"))
                .when(workService).registerAsPaidout(anyString(), anyString(), anyInt(), anyInt());

        // Must NOT propagate — the booking has already durably committed.
        assertDoesNotThrow(() ->
                observer.onInvoiceBooked(new InvoiceBookedEvent("i1", "c1", "p1", 6, 2026)));

        verify(workService).registerAsPaidout("c1", "p1", 6, 2026);
    }
}
