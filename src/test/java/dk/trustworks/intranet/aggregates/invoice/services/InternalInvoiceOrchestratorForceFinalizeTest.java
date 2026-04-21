package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
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

    private Invoice queuedInternal(String uuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INTERNAL);
        inv.setStatus(InvoiceStatus.QUEUED);
        return inv;
    }
}
