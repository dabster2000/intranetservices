package dk.trustworks.intranet.aggregates.invoice.economics.book;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the 2026-08-13 writer changes:
 *
 * <ul>
 *   <li>{@link InvoiceBookingAttemptWriter#markBookedAttemptOnly} — the ambient-transaction Tx2
 *       variant that must never touch the invoices row (the outer finalizeAutomatically
 *       transaction holds that row's lock; updating it from REQUIRES_NEW self-deadlocks).</li>
 *   <li>{@link InvoiceBookingAttemptWriter#markSuperseded} — a cancelled draft must not hide a
 *       POSTed attempt: the vendor may hold a booking under the invoice's idempotency key, so
 *       posted rows become NEEDS_RECONCILIATION instead of SUPERSEDED.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InvoiceBookingAttemptWriterMarkTest {

    @InjectMocks InvoiceBookingAttemptWriter writer;

    @Mock InvoiceBookingAttemptRepository attempts;
    @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void markBookedAttemptOnly_records_state_and_number_on_the_attempt() {
        InvoiceBookingAttempt a = pendingAttempt("att-1");
        when(attempts.findById("att-1")).thenReturn(a);

        writer.markBookedAttemptOnly("att-1", 50107);

        assertEquals(InvoiceBookingAttempt.State.BOOKED, a.getState());
        assertEquals(50107, a.getBookedNumber());
        assertNotNull(a.getCompletedAt());
        assertNull(a.getLastError());
        verify(attempts).persist(a);
    }

    @Test
    void markSuperseded_keeps_posted_attempts_visible_as_needs_reconciliation() {
        InvoiceBookingAttempt postedOne = pendingAttempt("att-posted");
        postedOne.setPostedAt(LocalDateTime.now().minusMinutes(10));
        InvoiceBookingAttempt unposted = pendingAttempt("att-unposted");
        when(attempts.listOpenByInvoice("inv-1")).thenReturn(List.of(postedOne, unposted));

        writer.markSuperseded("inv-1");

        assertEquals(InvoiceBookingAttempt.State.NEEDS_RECONCILIATION, postedOne.getState(),
                "a POSTed attempt may have produced a vendor booking — it must stay visible");
        assertNotNull(postedOne.getLastError());
        assertEquals(InvoiceBookingAttempt.State.SUPERSEDED, unposted.getState(),
                "an un-POSTed attempt is safe to supersede");
    }

    private InvoiceBookingAttempt pendingAttempt(String uuid) {
        InvoiceBookingAttempt a = new InvoiceBookingAttempt();
        a.setUuid(uuid);
        a.setState(InvoiceBookingAttempt.State.PENDING);
        return a;
    }
}
