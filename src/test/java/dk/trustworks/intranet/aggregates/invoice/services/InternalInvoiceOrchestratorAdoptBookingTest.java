package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttempt;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptWriter;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.model.Company;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InternalInvoiceOrchestrator#adoptVendorBooking(String, int)} — the
 * split-brain recovery behind {@code POST /invoices/internalservices/{uuid}/reconcile-booking}.
 *
 * <p>Failure shape being recovered (2026-08-13, invoice 603bdb0d): finalizeAutomatically
 * self-deadlocked recording Tx2, the local transaction rolled back to DRAFT, and e-conomic kept
 * booked invoice 50107. The adopt must verify the human-supplied booked number against the vendor
 * (existence + gross match) before recording anything, and must post the debtor-side voucher with
 * the vendor-verified gross as the transient grandTotal.
 */
@ExtendWith(MockitoExtension.class)
class InternalInvoiceOrchestratorAdoptBookingTest {

    @InjectMocks InternalInvoiceOrchestrator internal;

    @Mock InvoiceFinalizationOrchestrator issuerSide;
    @Mock EconomicsInvoiceService economicsInvoiceService;
    @Mock InvoiceRepository invoices;
    @Mock EconomicsAgreementResolver agreements;
    @Mock EconomicsBookingApiClient bookApi;
    @Mock InvoiceBookingAttemptRepository attemptRepo;
    @Mock InvoiceBookingAttemptWriter attemptWriter;

    private static final EconomicsAgreementResolver.Tokens TOKENS =
            new EconomicsAgreementResolver.Tokens("secret", "grant");

    @Test
    void adopt_happy_path_verifies_records_and_posts_debtor_voucher_in_order() {
        Invoice inv = settlementDraft("inv-1");
        when(invoices.findByUuid("inv-1")).thenReturn(Optional.of(inv));
        when(attemptRepo.listPostedUnresolvedByInvoice("inv-1"))
                .thenReturn(List.of(postedAttempt("att-1")));
        when(agreements.tokens("cyber-uuid")).thenReturn(TOKENS);
        when(bookApi.getBooked("secret", "grant", 50107)).thenReturn(booked(17078.70));

        Invoice result = internal.adoptVendorBooking("inv-1", 50107);

        InOrder inOrder = inOrder(attemptWriter, invoices, issuerSide);
        inOrder.verify(attemptWriter).markBooked("att-1", "inv-1", 50107);
        inOrder.verify(invoices).refresh(inv);
        inOrder.verify(issuerSide).postDebtorVoucherAfterReconcile(inv, null);
        assertEquals(17078.70, inv.getGrandTotal(), 0.001,
                "grandTotal must carry the vendor-verified gross into the debtor voucher");
        assertSame(inv, result);
    }

    @Test
    void adopt_rejects_gross_mismatch_and_records_nothing() {
        Invoice inv = settlementDraft("inv-2");
        when(invoices.findByUuid("inv-2")).thenReturn(Optional.of(inv));
        when(attemptRepo.listPostedUnresolvedByInvoice("inv-2"))
                .thenReturn(List.of(postedAttempt("att-2")));
        when(agreements.tokens("cyber-uuid")).thenReturn(TOKENS);
        // Vendor gross differs from the items-derived 17078.70 by more than the 0.05 tolerance.
        when(bookApi.getBooked("secret", "grant", 99999)).thenReturn(booked(12345.00));

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> internal.adoptVendorBooking("inv-2", 99999));

        assertEquals(409, thrown.getResponse().getStatus());
        verifyNoInteractions(attemptWriter);
        verify(issuerSide, never()).postDebtorVoucherAfterReconcile(any(), any());
    }

    @Test
    void adopt_rejects_when_no_posted_unresolved_attempt_exists() {
        Invoice inv = settlementDraft("inv-3");
        when(invoices.findByUuid("inv-3")).thenReturn(Optional.of(inv));
        when(attemptRepo.listPostedUnresolvedByInvoice("inv-3")).thenReturn(List.of());

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> internal.adoptVendorBooking("inv-3", 50107));

        assertEquals(409, thrown.getResponse().getStatus());
        verifyNoInteractions(bookApi, attemptWriter);
    }

    @Test
    void adopt_rejects_a_DIFFERENT_already_recorded_booked_number() {
        Invoice inv = settlementDraft("inv-4");
        inv.setEconomicsBookedNumber(50000);
        when(invoices.findByUuid("inv-4")).thenReturn(Optional.of(inv));

        assertThrows(BadRequestException.class, () -> internal.adoptVendorBooking("inv-4", 50107));

        verifyNoInteractions(attemptRepo, bookApi, attemptWriter);
    }

    // Completion mode: an earlier adopt recorded the booking but died before the debtor voucher
    // (2026-08-14, invoice 603bdb0d — the pre-fix refresh threw TransactionRequiredException).
    // Re-running with the SAME number must skip the recording and just verify + post the voucher.
    @Test
    void adopt_with_matching_recorded_number_completes_voucher_only() {
        Invoice inv = settlementDraft("inv-7");
        inv.setEconomicsBookedNumber(50107);
        when(invoices.findByUuid("inv-7")).thenReturn(Optional.of(inv));
        when(agreements.tokens("cyber-uuid")).thenReturn(TOKENS);
        when(bookApi.getBooked("secret", "grant", 50107)).thenReturn(booked(17078.70));

        Invoice result = internal.adoptVendorBooking("inv-7", 50107);

        verify(issuerSide).postDebtorVoucherAfterReconcile(inv, null);
        assertEquals(17078.70, inv.getGrandTotal(), 0.001);
        verifyNoInteractions(attemptRepo, attemptWriter);
        verify(invoices, never()).refresh(any());
        assertSame(inv, result);
    }

    // ── the 2026-09 half-bookings: complete the debtor leg in the first OPEN period ─────────

    /**
     * Five internals dated 2026-07-31 were booked at the issuer while Trustworks A/S had July 2026
     * barred. Their debtor voucher can only go into August, so the date must reach the voucher
     * service — and the invoice itself must stay dated July.
     */
    @Test
    void completion_mode_passes_the_voucher_date_through_and_closes_the_half_booking_on_success() {
        Invoice inv = settlementDraft("inv-8");
        inv.setEconomicsBookedNumber(50107);
        inv.setInvoicedate(LocalDate.of(2026, 7, 31));
        inv.setEconomicsStatus(EconomicsInvoiceStatus.PARTIALLY_UPLOADED);
        when(invoices.findByUuid("inv-8")).thenReturn(Optional.of(inv));
        when(agreements.tokens("cyber-uuid")).thenReturn(TOKENS);
        when(bookApi.getBooked("secret", "grant", 50107)).thenReturn(booked(17078.70));
        when(issuerSide.postDebtorVoucherAfterReconcile(inv, LocalDate.of(2026, 8, 1))).thenReturn(true);
        when(invoices.markDebtorVoucherPosted("inv-8")).thenReturn(1);

        internal.adoptVendorBooking("inv-8", 50107, LocalDate.of(2026, 8, 1));

        InOrder inOrder = inOrder(issuerSide, invoices);
        inOrder.verify(issuerSide).postDebtorVoucherAfterReconcile(inv, LocalDate.of(2026, 8, 1));
        inOrder.verify(invoices).markDebtorVoucherPosted("inv-8");
        inOrder.verify(invoices).refresh(inv);
        assertEquals(LocalDate.of(2026, 7, 31), inv.getInvoicedate(),
                "the override dates the voucher, never the invoice");
    }

    @Test
    void completion_mode_leaves_the_status_alone_when_the_debtor_voucher_is_refused() {
        Invoice inv = settlementDraft("inv-9");
        inv.setEconomicsBookedNumber(50107);
        inv.setInvoicedate(LocalDate.of(2026, 7, 31));
        when(invoices.findByUuid("inv-9")).thenReturn(Optional.of(inv));
        when(agreements.tokens("cyber-uuid")).thenReturn(TOKENS);
        when(bookApi.getBooked("secret", "grant", 50107)).thenReturn(booked(17078.70));
        when(issuerSide.postDebtorVoucherAfterReconcile(inv, LocalDate.of(2026, 8, 1))).thenReturn(false);

        internal.adoptVendorBooking("inv-9", 50107, LocalDate.of(2026, 8, 1));

        verify(invoices, never()).markDebtorVoucherPosted(any());
        verify(invoices, never()).refresh(any());
    }

    @Test
    void a_voucher_date_before_the_invoice_date_or_in_the_future_is_refused_before_any_vendor_call() {
        Invoice inv = settlementDraft("inv-10");
        inv.setEconomicsBookedNumber(50107);
        inv.setInvoicedate(LocalDate.of(2026, 7, 31));
        when(invoices.findByUuid("inv-10")).thenReturn(Optional.of(inv));

        assertThrows(BadRequestException.class,
                () -> internal.adoptVendorBooking("inv-10", 50107, LocalDate.of(2026, 6, 30)),
                "cost before revenue is never intended");
        assertThrows(BadRequestException.class,
                () -> internal.adoptVendorBooking("inv-10", 50107, LocalDate.now().plusDays(1)));

        verifyNoInteractions(bookApi, issuerSide, attemptWriter);
    }

    @Test
    void adopt_rejects_non_internal_type_and_non_positive_number() {
        Invoice inv = settlementDraft("inv-5");
        inv.setType(InvoiceType.INVOICE);
        when(invoices.findByUuid("inv-5")).thenReturn(Optional.of(inv));

        assertThrows(BadRequestException.class, () -> internal.adoptVendorBooking("inv-5", 50107));
        assertThrows(BadRequestException.class, () -> internal.adoptVendorBooking("inv-5", 0));

        verifyNoInteractions(attemptWriter);
    }

    @Test
    void expectedGross_derives_vat_inclusive_total_from_items() {
        // The July-2025 settlement: 10318.54 + 3344.42 = 13662.96 net, 25% VAT → 17078.70 gross.
        Invoice inv = settlementDraft("inv-6");

        assertEquals(17078.70, InternalInvoiceOrchestrator.expectedGrossFromItems(inv), 0.001);
    }

    private Invoice settlementDraft(String uuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INTERNAL_SERVICE);
        inv.setStatus(InvoiceStatus.DRAFT);
        inv.vat = 25;
        Company company = new Company();
        company.setUuid("cyber-uuid");
        inv.setCompany(company);
        inv.invoiceitems = new ArrayList<>();
        inv.invoiceitems.add(item(10318.54));
        inv.invoiceitems.add(item(3344.42));
        return inv;
    }

    private InvoiceItem item(double rate) {
        InvoiceItem it = new InvoiceItem();
        it.rate = rate;
        it.hours = 1.0;
        return it;
    }

    private InvoiceBookingAttempt postedAttempt(String uuid) {
        InvoiceBookingAttempt a = new InvoiceBookingAttempt();
        a.setUuid(uuid);
        a.setState(InvoiceBookingAttempt.State.PENDING);
        a.setPostedAt(LocalDateTime.now().minusHours(2));
        a.setDraftInvoiceNumber(49);
        return a;
    }

    private EconomicsBookedInvoice booked(double gross) {
        EconomicsBookedInvoice b = new EconomicsBookedInvoice();
        b.setBookedInvoiceNumber(50107);
        b.setGrossAmount(gross);
        return b;
    }
}
