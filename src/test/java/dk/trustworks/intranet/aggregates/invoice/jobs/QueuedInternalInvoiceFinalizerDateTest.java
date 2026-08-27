package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.economics.period.AccountingPeriodPreflight;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Which accounting period the nightly job books an internal invoice into.
 *
 * <p>The job fires when the client pays, which is typically one to three months after the work was
 * billed out — 239 of 273 booked internals are dated later than the invoice they mirror. Inside a
 * financial year that only shifts the monthly phasing; across 30 June it misstates the
 * subsidiaries' annual revenue, and it did so at each of the last three year-ends (nine invoices,
 * ~1.86M DKK, every one dated July or August).
 *
 * <p>So: use the source period when e-conomic confirms it is open, and today otherwise. The second
 * half matters as much as the first — a job that insisted on a closed source period would fail
 * every night instead of booking late, which is worse than the problem being fixed.
 */
@ExtendWith(MockitoExtension.class)
class QueuedInternalInvoiceFinalizerDateTest {

    @InjectMocks QueuedInternalInvoiceFinalizer finalizer;

    @Mock AccountingPeriodPreflight periodPreflight;

    private static final String ISSUER = "issuer-uuid";
    private static final LocalDate CLIENT_INVOICE_DATE = LocalDate.of(2026, 6, 30);

    @Test
    void back_dates_to_the_source_period_when_e_conomic_confirms_it_is_open() {
        Invoice inv = internal();
        when(periodPreflight.isKnownOpen(ISSUER, CLIENT_INVOICE_DATE)).thenReturn(true);

        finalizer.applyFinalizationDate(inv, CLIENT_INVOICE_DATE, "client invoice 28114");

        assertEquals(CLIENT_INVOICE_DATE, inv.getInvoicedate(),
                "FY2025/26 work must not land in FY2026/27 when June is still open");
        assertEquals(CLIENT_INVOICE_DATE.plusDays(1), inv.getDuedate());
    }

    @Test
    void falls_back_to_today_when_the_source_period_is_closed_or_barred() {
        Invoice inv = internal();
        when(periodPreflight.isKnownOpen(ISSUER, CLIENT_INVOICE_DATE)).thenReturn(false);

        finalizer.applyFinalizationDate(inv, CLIENT_INVOICE_DATE, "client invoice 28114");

        assertEquals(LocalDate.now(), inv.getInvoicedate(),
                "booking late beats not booking at all");
        assertEquals(LocalDate.now().plusDays(1), inv.getDuedate());
    }

    /** A vendor outage reports UNKNOWN, which isKnownOpen returns as false — so: today. */
    @Test
    void falls_back_to_today_when_the_period_cannot_be_confirmed() {
        Invoice inv = internal();
        when(periodPreflight.isKnownOpen(anyString(), any())).thenReturn(false);

        finalizer.applyFinalizationDate(inv, CLIENT_INVOICE_DATE, "client invoice 28114");

        assertEquals(LocalDate.now(), inv.getInvoicedate());
    }

    @Test
    void uses_today_when_there_is_no_source_date_at_all() {
        Invoice inv = internal();

        finalizer.applyFinalizationDate(inv, null, "no source");

        assertEquals(LocalDate.now(), inv.getInvoicedate());
        verifyNoInteractions(periodPreflight);
    }

    /**
     * Never forward-date. A source date after today would post into the future, which no accounting
     * convention wants and which the manual force-create path rejects outright.
     */
    @Test
    void never_uses_a_future_source_date() {
        Invoice inv = internal();
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        finalizer.applyFinalizationDate(inv, tomorrow, "a source dated in the future");

        assertEquals(LocalDate.now(), inv.getInvoicedate());
        verifyNoInteractions(periodPreflight);
    }

    @Test
    void uses_today_when_the_invoice_has_no_issuing_company() {
        Invoice inv = new Invoice();
        inv.setUuid("invoice-uuid");

        finalizer.applyFinalizationDate(inv, CLIENT_INVOICE_DATE, "client invoice 28114");

        assertEquals(LocalDate.now(), inv.getInvoicedate());
        verifyNoInteractions(periodPreflight);
    }

    /** Today is inside an open period by definition of "open", but the check must still be made. */
    @Test
    void a_same_day_source_still_consults_the_period() {
        Invoice inv = internal();
        when(periodPreflight.isKnownOpen(eq(ISSUER), eq(LocalDate.now()))).thenReturn(true);

        finalizer.applyFinalizationDate(inv, LocalDate.now(), "client invoice paid same day");

        assertEquals(LocalDate.now(), inv.getInvoicedate());
        verify(periodPreflight).isKnownOpen(ISSUER, LocalDate.now());
    }

    // ── settlement internals carry a month rather than a source invoice ──────────────────────

    @Test
    void a_settlement_internal_is_dated_at_the_end_of_the_month_it_settles() {
        assertEquals(LocalDate.of(2026, 2, 28),
                QueuedInternalInvoiceFinalizer.endOfSettlementMonth(settlement(2026, 2)),
                "short months must land on the real last day");
        assertEquals(LocalDate.of(2026, 6, 30),
                QueuedInternalInvoiceFinalizer.endOfSettlementMonth(settlement(2026, 6)));
        assertEquals(LocalDate.of(2026, 12, 31),
                QueuedInternalInvoiceFinalizer.endOfSettlementMonth(settlement(2026, 12)),
                "December must not roll into the next year");
    }

    @Test
    void a_settlement_internal_with_no_or_invalid_period_has_no_source_date() {
        assertNull(QueuedInternalInvoiceFinalizer.endOfSettlementMonth(settlement(null, null)));
        assertNull(QueuedInternalInvoiceFinalizer.endOfSettlementMonth(settlement(2026, null)));
        assertNull(QueuedInternalInvoiceFinalizer.endOfSettlementMonth(settlement(2026, 0)));
        assertNull(QueuedInternalInvoiceFinalizer.endOfSettlementMonth(settlement(2026, 13)));
    }

    private static Invoice internal() {
        Company company = new Company();
        company.setUuid(ISSUER);
        company.setName("Trustworks Technology ApS");
        Invoice inv = new Invoice();
        inv.setUuid("invoice-uuid");
        inv.setCompany(company);
        return inv;
    }

    private static Invoice settlement(Integer year, Integer month) {
        Invoice inv = internal();
        inv.setSettlementYear(year);
        inv.setSettlementMonth(month);
        return inv;
    }
}
