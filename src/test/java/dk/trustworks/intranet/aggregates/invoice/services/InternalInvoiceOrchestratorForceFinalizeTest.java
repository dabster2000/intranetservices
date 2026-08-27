package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InternalInvoiceOrchestrator#forceFinalizeQueued(String)}.
 *
 * <p>This is the entry point invoked by
 * {@code POST /invoices/{invoiceuuid}/force-create-queued} — it validates the invoice
 * is QUEUED and INTERNAL, sets invoicedate=today / duedate=tomorrow (mirroring the
 * nightly batchlet), then delegates to {@link InternalInvoiceOrchestrator#finalizeAutomatically}.
 *
 * <p>The old path used the legacy voucher flow
 * ({@code InvoiceEconomicsUploadService.queueUploads} + {@code processUploads}), which
 * broke after the 2026-04-16 PDF-refactor: both ISSUER and DEBTOR vouchers failed on
 * the "No PDF available" precondition. Routing through {@code finalizeAutomatically}
 * books via Q2C (no local PDF needed) and the DEBTOR-side voucher now fetches the
 * PDF from e-conomic via {@link EconomicsInvoiceService#loadInvoicePdfBytes}.
 */
@ExtendWith(MockitoExtension.class)
class InternalInvoiceOrchestratorForceFinalizeTest {

    @InjectMocks InternalInvoiceOrchestrator internal;

    @Mock InvoiceFinalizationOrchestrator issuerSide;
    @Mock EconomicsInvoiceService economicsInvoiceService;
    @Mock InvoiceRepository invoices;

    @Test
    void forceFinalizeQueued_QUEUED_internal_invoice_sets_dates_and_delegates_to_finalizeAutomatically() {
        Invoice queued = queuedInternal("inv-1");
        when(invoices.findByUuid("inv-1")).thenReturn(Optional.of(queued));

        Invoice booked = queuedInternal("inv-1");
        booked.setStatus(InvoiceStatus.CREATED);
        when(issuerSide.createDraft("inv-1")).thenReturn(queued);
        when(issuerSide.bookDraft("inv-1", null)).thenReturn(booked);

        Invoice result = internal.forceFinalizeQueued("inv-1");

        assertEquals(LocalDate.now(), queued.getInvoicedate(),
                "invoicedate must be set to today before finalizing (matches nightly batchlet)");
        assertEquals(LocalDate.now().plusDays(1), queued.getDuedate(),
                "duedate must be set to tomorrow");
        verify(issuerSide).createDraft("inv-1");
        verify(issuerSide).bookDraft("inv-1", null);
        assertSame(booked, result);
    }

    @Test
    void forceFinalizeQueued_also_accepts_INTERNAL_SERVICE_type() {
        Invoice queued = queuedInternal("inv-2");
        queued.setType(InvoiceType.INTERNAL_SERVICE);
        when(invoices.findByUuid("inv-2")).thenReturn(Optional.of(queued));
        Invoice booked = queuedInternal("inv-2");
        booked.setType(InvoiceType.INTERNAL_SERVICE);
        booked.setStatus(InvoiceStatus.CREATED);
        when(issuerSide.createDraft("inv-2")).thenReturn(queued);
        when(issuerSide.bookDraft("inv-2", null)).thenReturn(booked);

        Invoice result = internal.forceFinalizeQueued("inv-2");

        assertSame(booked, result);
    }

    @Test
    void forceFinalizeQueued_rejects_non_QUEUED_status() {
        Invoice inv = queuedInternal("inv-3");
        inv.setStatus(InvoiceStatus.CREATED);
        when(invoices.findByUuid("inv-3")).thenReturn(Optional.of(inv));

        BadRequestException thrown = assertThrows(
                BadRequestException.class,
                () -> internal.forceFinalizeQueued("inv-3"));

        assertTrue(thrown.getMessage().contains("QUEUED"),
                "error message should identify required status, got: " + thrown.getMessage());
        verifyNoInteractions(issuerSide);
    }

    @Test
    void forceFinalizeQueued_rejects_non_INTERNAL_type() {
        Invoice inv = queuedInternal("inv-4");
        inv.setType(InvoiceType.INVOICE);
        when(invoices.findByUuid("inv-4")).thenReturn(Optional.of(inv));

        BadRequestException thrown = assertThrows(
                BadRequestException.class,
                () -> internal.forceFinalizeQueued("inv-4"));

        assertTrue(thrown.getMessage().contains("INTERNAL"),
                "error message should identify allowed types, got: " + thrown.getMessage());
        verifyNoInteractions(issuerSide);
    }

    @Test
    void forceFinalizeQueued_throws_NotFound_when_invoice_missing() {
        when(invoices.findByUuid("nope")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> internal.forceFinalizeQueued("nope"));

        verifyNoInteractions(issuerSide);
    }

    /**
     * The 2026-08-27 case: five TWC internals mirroring 30.06.2026 client invoices had to book into
     * June, not into an August that TWC has barred for all of FY 2026/27. Without an explicit date
     * they are unbookable, and the four that did go through (TWT) carried FY 2025/26 revenue into
     * the new financial year.
     */
    @Test
    void forceFinalizeQueued_honours_an_explicit_backdated_invoicedate() {
        Invoice queued = queuedInternal("inv-5");
        when(invoices.findByUuid("inv-5")).thenReturn(Optional.of(queued));
        Invoice booked = queuedInternal("inv-5");
        booked.setStatus(InvoiceStatus.CREATED);
        when(issuerSide.createDraft("inv-5")).thenReturn(queued);
        when(issuerSide.bookDraft("inv-5", null)).thenReturn(booked);

        LocalDate clientInvoiceDate = LocalDate.of(2026, 6, 30);
        internal.forceFinalizeQueued("inv-5", clientInvoiceDate);

        assertEquals(clientInvoiceDate, queued.getInvoicedate(),
                "explicit date must win over today so the internal lands in the period it mirrors");
        assertEquals(clientInvoiceDate.plusDays(1), queued.getDuedate(),
                "duedate must keep following invoicedate by one day");
    }

    @Test
    void forceFinalizeQueued_falls_back_to_today_when_no_date_is_given() {
        Invoice queued = queuedInternal("inv-6");
        when(invoices.findByUuid("inv-6")).thenReturn(Optional.of(queued));
        when(issuerSide.createDraft("inv-6")).thenReturn(queued);
        when(issuerSide.bookDraft("inv-6", null)).thenReturn(queued);

        internal.forceFinalizeQueued("inv-6", null);

        assertEquals(LocalDate.now(), queued.getInvoicedate(),
                "null must preserve the pre-existing default, so the nightly convention is unchanged");
    }

    @Test
    void forceFinalizeQueued_rejects_a_future_invoicedate() {
        Invoice queued = queuedInternal("inv-7");
        when(invoices.findByUuid("inv-7")).thenReturn(Optional.of(queued));

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> internal.forceFinalizeQueued("inv-7", LocalDate.now().plusDays(1)));

        assertTrue(thrown.getMessage().contains("future"),
                "error should name the problem, got: " + thrown.getMessage());
        verifyNoInteractions(issuerSide);
    }

    /**
     * createDraft and bookDraft share one transaction, so a booking failure rolls back
     * economicsDraftNumber and nothing local ever names that e-conomic draft again. It must be
     * deleted on the way out — TWC accumulated drafts 73/74/75 exactly this way, and the nightly
     * batchlet would have added one more every night.
     */
    @Test
    void finalizeAutomatically_deletes_the_draft_when_booking_is_definitively_refused() {
        Invoice queued = queuedInternal("inv-8");
        queued.setEconomicsDraftNumber(5);
        when(invoices.findByUuid("inv-8")).thenReturn(Optional.of(queued));
        when(issuerSide.createDraft("inv-8")).thenReturn(queued);
        when(issuerSide.bookDraft("inv-8", null)).thenThrow(new WebApplicationException(
                "e-conomic booking API HTTP 400: invoice date lies within a barred period",
                Response.status(400).build()));

        assertThrows(WebApplicationException.class, () -> internal.finalizeAutomatically("inv-8"));

        verify(issuerSide).deleteUnbookedDraft("inv-8", 5);
    }

    /** An ambiguous outcome must leave the draft alone — it is the only evidence of what happened. */
    @Test
    void finalizeAutomatically_keeps_the_draft_when_the_vendor_outcome_is_unknown() {
        Invoice queued = queuedInternal("inv-9");
        queued.setEconomicsDraftNumber(6);
        when(invoices.findByUuid("inv-9")).thenReturn(Optional.of(queued));
        when(issuerSide.createDraft("inv-9")).thenReturn(queued);
        when(issuerSide.bookDraft("inv-9", null)).thenThrow(new WebApplicationException(
                "e-conomic booking API HTTP 400: PayloadChanged",
                Response.status(400).build()));

        assertThrows(WebApplicationException.class, () -> internal.finalizeAutomatically("inv-9"));

        verify(issuerSide, never()).deleteUnbookedDraft(anyString(), anyInt());
    }

    private Invoice queuedInternal(String uuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INTERNAL);
        inv.setStatus(InvoiceStatus.QUEUED);
        return inv;
    }
}
