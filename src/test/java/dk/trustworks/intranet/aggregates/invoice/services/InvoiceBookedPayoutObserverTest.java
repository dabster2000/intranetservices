package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.dao.workservice.services.WorkService;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * Regression pin for the 2026-07 prod incident: AFTER_SUCCESS observers run in the
     * afterCompletion callback with the COMPLETED booking transaction still associated with
     * the thread, so registerAsPaidout must open its OWN transaction. The default
     * {@code @Transactional} (REQUIRED) joins the dead transaction and Narayana throws
     * InactiveTransactionException — the payout was silently lost on every booked invoice.
     */
    @Test
    void registerAsPaidout_must_use_REQUIRES_NEW_because_it_runs_after_commit() throws Exception {
        Method m = WorkService.class.getMethod(
                "registerAsPaidout", String.class, String.class, int.class, int.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "registerAsPaidout must be @Transactional");
        assertEquals(Transactional.TxType.REQUIRES_NEW, tx.value(),
                "registerAsPaidout is called from an AFTER_SUCCESS observer where the completed "
                + "booking tx is still thread-associated; REQUIRED joins the dead tx and throws "
                + "InactiveTransactionException, losing the payout");
    }

    /**
     * The payout must only run once the booking has durably committed — DURING/IN_PROGRESS
     * would reintroduce the split-brain where a payout failure rolls back the booking that
     * e-conomic already accepted.
     */
    @Test
    void observer_fires_only_AFTER_SUCCESS_of_the_booking_transaction() throws Exception {
        Method m = InvoiceBookedPayoutObserver.class.getDeclaredMethod(
                "onInvoiceBooked", InvoiceBookedEvent.class);
        Parameter eventParam = m.getParameters()[0];
        Observes observes = eventParam.getAnnotation(Observes.class);
        assertNotNull(observes, "onInvoiceBooked must observe InvoiceBookedEvent");
        assertEquals(TransactionPhase.AFTER_SUCCESS, observes.during());
    }
}
