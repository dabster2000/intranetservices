package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InternalInvoiceOrchestrator#refinalizePendingReview(String)}.
 *
 * <p>This is the recovery entry point behind
 * {@code POST /invoices/internalservices/{invoiceuuid}/book} for invoices stranded in
 * PENDING_REVIEW (e-conomic draft posted, booking never ran). It must NOT call
 * {@code bookDraft} directly in a fresh request — the debtor-side voucher reads the
 * {@code @Transient} grandTotal set during {@code createDraft}, so a separate-request
 * book posts a 0.00 DKK supplier voucher with no error. Instead it cancels the stranded
 * draft (own committed transaction) and re-finalizes through
 * {@link InternalInvoiceOrchestrator#finalizeAutomatically}.
 */
@ExtendWith(MockitoExtension.class)
class InternalInvoiceOrchestratorRefinalizeTest {

    @InjectMocks InternalInvoiceOrchestrator internal;

    @Mock InvoiceFinalizationOrchestrator issuerSide;
    @Mock EconomicsInvoiceService economicsInvoiceService;
    @Mock InvoiceRepository invoices;

    @Test
    void refinalize_PENDING_REVIEW_internal_service_cancels_then_finalizes_in_order() {
        Invoice stranded = pendingReviewInternalService("inv-1");
        when(invoices.findByUuid("inv-1")).thenReturn(Optional.of(stranded));

        Invoice booked = pendingReviewInternalService("inv-1");
        booked.setStatus(InvoiceStatus.CREATED);
        when(issuerSide.bookDraft("inv-1", null)).thenReturn(booked);

        Invoice result = internal.refinalizePendingReview("inv-1");

        InOrder inOrder = inOrder(issuerSide);
        inOrder.verify(issuerSide).cancelFinalization("inv-1");
        inOrder.verify(issuerSide).createDraft("inv-1");
        inOrder.verify(issuerSide).bookDraft("inv-1", null);
        assertSame(booked, result);
    }

    @Test
    void refinalize_also_accepts_INTERNAL_type() {
        Invoice stranded = pendingReviewInternalService("inv-2");
        stranded.setType(InvoiceType.INTERNAL);
        when(invoices.findByUuid("inv-2")).thenReturn(Optional.of(stranded));
        Invoice booked = pendingReviewInternalService("inv-2");
        booked.setType(InvoiceType.INTERNAL);
        booked.setStatus(InvoiceStatus.CREATED);
        when(issuerSide.bookDraft("inv-2", null)).thenReturn(booked);

        Invoice result = internal.refinalizePendingReview("inv-2");

        assertSame(booked, result);
    }

    @Test
    void refinalize_rejects_non_PENDING_REVIEW_status() {
        Invoice inv = pendingReviewInternalService("inv-3");
        inv.setStatus(InvoiceStatus.DRAFT);
        when(invoices.findByUuid("inv-3")).thenReturn(Optional.of(inv));

        BadRequestException thrown = assertThrows(
                BadRequestException.class,
                () -> internal.refinalizePendingReview("inv-3"));

        assertTrue(thrown.getMessage().contains("PENDING_REVIEW"),
                "error message should identify required status, got: " + thrown.getMessage());
        verifyNoInteractions(issuerSide);
    }

    @Test
    void refinalize_rejects_non_INTERNAL_type() {
        Invoice inv = pendingReviewInternalService("inv-4");
        inv.setType(InvoiceType.INVOICE);
        when(invoices.findByUuid("inv-4")).thenReturn(Optional.of(inv));

        BadRequestException thrown = assertThrows(
                BadRequestException.class,
                () -> internal.refinalizePendingReview("inv-4"));

        assertTrue(thrown.getMessage().contains("INTERNAL"),
                "error message should identify allowed types, got: " + thrown.getMessage());
        verifyNoInteractions(issuerSide);
    }

    @Test
    void refinalize_throws_NotFound_when_invoice_missing() {
        when(invoices.findByUuid("nope")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> internal.refinalizePendingReview("nope"));

        verifyNoInteractions(issuerSide);
    }

    @Test
    void refinalize_does_not_finalize_when_cancel_fails() {
        Invoice stranded = pendingReviewInternalService("inv-5");
        when(invoices.findByUuid("inv-5")).thenReturn(Optional.of(stranded));
        doThrow(new IllegalStateException("cancel failed"))
                .when(issuerSide).cancelFinalization("inv-5");

        assertThrows(IllegalStateException.class, () -> internal.refinalizePendingReview("inv-5"));

        verify(issuerSide, never()).createDraft(anyString());
        verify(issuerSide, never()).bookDraft(anyString(), any());
    }

    private Invoice pendingReviewInternalService(String uuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INTERNAL_SERVICE);
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        return inv;
    }
}
