package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.economics.period.AccountingPeriodPreflight;
import dk.trustworks.intranet.aggregates.invoice.economics.period.AccountingPeriodPreflight.PeriodState;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * <p>An internal invoice is posted in TWO agreements on the same date, and barring is per
 * agreement. Until 2026-09-11 only the issuer was asked, and five invoices dated 2026-07-31 were
 * booked at Trustworks Technology ApS while Trustworks A/S had July barred — half-booked. So:
 * the source date when both companies confirm it open; else the first later month both confirm
 * open; else today. The last branch matters as much as the first — a job that insisted on a
 * closed period would fail every night instead of booking late, which is worse than the problem
 * being fixed.
 */
@ExtendWith(MockitoExtension.class)
class QueuedInternalInvoiceFinalizerDateTest {

    @InjectMocks QueuedInternalInvoiceFinalizer finalizer;

    @Mock AccountingPeriodPreflight periodPreflight;

    private static final String ISSUER = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";   // Technology
    private static final String DEBTOR = "d8894494-2fb4-4f72-9e05-e6032e6dd691";   // A/S
    private static final LocalDate JUL_31 = LocalDate.of(2026, 7, 31);
    private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

    // ── the source period is open on both sides ─────────────────────────────────────────────

    @Test
    void back_dates_to_the_source_period_when_both_companies_confirm_it_open() {
        Invoice inv = internal();
        whenIssuerSays(Map.of(JUL_31, PeriodState.OPEN));
        whenDebtorSays(Map.of(JUL_31, PeriodState.OPEN));

        finalizer.applyFinalizationDate(inv, JUL_31, "client invoice 28190");

        assertEquals(JUL_31, inv.getInvoicedate(),
                "FY2025/26-style work must not drift into a later period when its own is open");
        assertEquals(JUL_31.plusDays(1), inv.getDuedate());
    }

    // ── the 2026-09-09 shape: open at the issuer, barred at the debtor ──────────────────────

    /**
     * Invoice 7803f536: July open at Technology, barred at A/S. The old code chose 31 July on the
     * issuer's word alone and the debtor voucher was refused after the issuer had booked. Now the
     * job must skip July and take the first later month BOTH confirm open — August.
     */
    @Test
    void skips_a_source_period_the_debtor_has_barred_and_takes_the_first_later_month_open_in_both() {
        Invoice inv = internal();
        whenIssuerSays(Map.of(JUL_31, PeriodState.OPEN, AUG_1, PeriodState.OPEN));
        whenDebtorSays(Map.of(JUL_31, PeriodState.BLOCKED, AUG_1, PeriodState.OPEN));

        finalizer.applyFinalizationDate(inv, JUL_31, "client invoice 28190");

        assertEquals(AUG_1, inv.getInvoicedate(),
                "July is barred at A/S; August is the first month both can post in");
        assertEquals(AUG_1.plusDays(1), inv.getDuedate());
    }

    @Test
    void skips_a_source_period_the_issuer_has_barred_too() {
        Invoice inv = internal();
        whenIssuerSays(Map.of(JUL_31, PeriodState.BLOCKED, AUG_1, PeriodState.OPEN));
        whenDebtorSays(Map.of(JUL_31, PeriodState.OPEN, AUG_1, PeriodState.OPEN));

        finalizer.applyFinalizationDate(inv, JUL_31, "client invoice 28190");

        assertEquals(AUG_1, inv.getInvoicedate());
    }

    /** A month one side leaves UNKNOWN is not "open in both" — keep walking. */
    @Test
    void a_later_month_must_be_confirmed_open_by_both_not_merely_unblocked() {
        Invoice inv = internal();
        whenIssuerSays(Map.of(JUL_31, PeriodState.OPEN, AUG_1, PeriodState.UNKNOWN));
        whenDebtorSays(Map.of(JUL_31, PeriodState.BLOCKED, AUG_1, PeriodState.OPEN));

        finalizer.applyFinalizationDate(inv, JUL_31, "client invoice 28190");

        assertEquals(LocalDate.now(), inv.getInvoicedate(),
                "August is unconfirmed at the issuer, and there is no month after it before today");
    }

    // ── nothing before today is open on both sides → today, the old behaviour ──────────────

    @Test
    void falls_back_to_today_when_the_source_period_and_every_month_between_are_blocked() {
        Invoice inv = internal();
        whenIssuerSays(Map.of(JUL_31, PeriodState.BLOCKED, AUG_1, PeriodState.BLOCKED));
        whenDebtorSays(Map.of(JUL_31, PeriodState.BLOCKED, AUG_1, PeriodState.BLOCKED));

        finalizer.applyFinalizationDate(inv, JUL_31, "client invoice 28190");

        assertEquals(LocalDate.now(), inv.getInvoicedate(), "booking late beats not booking at all");
        assertEquals(LocalDate.now().plusDays(1), inv.getDuedate());
    }

    /** A vendor outage reports UNKNOWN everywhere — so: today. A hiccup must never move a period. */
    @Test
    void falls_back_to_today_when_nothing_can_be_confirmed() {
        Invoice inv = internal();
        when(periodPreflight.classifyDates(anyString(), any())).thenAnswer(a -> allUnknown(a.getArgument(1)));

        finalizer.applyFinalizationDate(inv, JUL_31, "client invoice 28190");

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

        finalizer.applyFinalizationDate(inv, LocalDate.now().plusDays(1), "a source dated in the future");

        assertEquals(LocalDate.now(), inv.getInvoicedate());
        verifyNoInteractions(periodPreflight);
    }

    @Test
    void uses_today_when_the_invoice_has_no_issuing_company() {
        Invoice inv = new Invoice();
        inv.setUuid("invoice-uuid");
        inv.setType(InvoiceType.INTERNAL);

        finalizer.applyFinalizationDate(inv, JUL_31, "client invoice 28190");

        assertEquals(LocalDate.now(), inv.getInvoicedate());
        verifyNoInteractions(periodPreflight);
    }

    // ── who is asked ────────────────────────────────────────────────────────────────────────

    /** An internal with no debtor recorded posts no debtor voucher, so only the issuer is asked. */
    @Test
    void an_internal_without_a_debtor_consults_only_the_issuer() {
        Invoice inv = internal();
        inv.setDebtorCompanyuuid(null);
        whenIssuerSays(Map.of(JUL_31, PeriodState.OPEN));

        finalizer.applyFinalizationDate(inv, JUL_31, "client invoice 28190");

        assertEquals(JUL_31, inv.getInvoicedate());
        verify(periodPreflight, never()).classifyDates(eq(DEBTOR), any());
    }

    /** However many months lie between, each agreement is read exactly once. */
    @Test
    void reads_each_agreement_once_for_all_candidate_dates() {
        Invoice inv = internal();
        LocalDate longAgo = LocalDate.now().minusMonths(6).withDayOfMonth(15);
        when(periodPreflight.classifyDates(anyString(), any())).thenAnswer(a -> allUnknown(a.getArgument(1)));

        finalizer.applyFinalizationDate(inv, longAgo, "an old client invoice");

        verify(periodPreflight, times(1)).classifyDates(eq(ISSUER), any());
        verify(periodPreflight, times(1)).classifyDates(eq(DEBTOR), any());
    }

    /** Today is inside an open period by definition of "open", but the check must still be made. */
    @Test
    void a_same_day_source_still_consults_both_companies() {
        Invoice inv = internal();
        whenIssuerSays(Map.of(LocalDate.now(), PeriodState.OPEN));
        whenDebtorSays(Map.of(LocalDate.now(), PeriodState.OPEN));

        finalizer.applyFinalizationDate(inv, LocalDate.now(), "client invoice paid same day");

        assertEquals(LocalDate.now(), inv.getInvoicedate());
        verify(periodPreflight).classifyDates(eq(ISSUER), eq(List.of(LocalDate.now())));
        verify(periodPreflight).classifyDates(eq(DEBTOR), eq(List.of(LocalDate.now())));
    }

    // ── the candidate list itself ───────────────────────────────────────────────────────────

    @Test
    void candidates_are_the_source_date_then_the_first_of_each_later_month_before_the_current_one() {
        assertEquals(List.of(JUL_31, AUG_1, SEP_1),
                QueuedInternalInvoiceFinalizer.candidateDates(JUL_31, LocalDate.of(2026, 10, 15)));
        assertEquals(List.of(JUL_31, AUG_1),
                QueuedInternalInvoiceFinalizer.candidateDates(JUL_31, LocalDate.of(2026, 9, 11)),
                "today's own month is not a candidate — today itself is the fallback");
        assertEquals(List.of(LocalDate.of(2026, 9, 3)),
                QueuedInternalInvoiceFinalizer.candidateDates(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 11)),
                "a source in the current month has nothing between it and today");
        assertEquals(List.of(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 1), AUG_1),
                QueuedInternalInvoiceFinalizer.candidateDates(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 9, 11)),
                "crossing 30 June lists July first — the first month of the new year");
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

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** Technology → A/S, the 7803f536 pair. */
    private static Invoice internal() {
        Company company = new Company();
        company.setUuid(ISSUER);
        company.setName("Trustworks Technology ApS");
        Invoice inv = new Invoice();
        inv.setUuid("invoice-uuid");
        inv.setType(InvoiceType.INTERNAL);
        inv.setCompany(company);
        inv.setDebtorCompanyuuid(DEBTOR);
        return inv;
    }

    private static Invoice settlement(Integer year, Integer month) {
        Invoice inv = internal();
        inv.setSettlementYear(year);
        inv.setSettlementMonth(month);
        return inv;
    }

    /** Stubs the issuer's answer: dates not listed are UNKNOWN, as the real classifier reports them. */
    private void whenIssuerSays(Map<LocalDate, PeriodState> states) {
        when(periodPreflight.classifyDates(eq(ISSUER), any())).thenAnswer(a -> answer(a.getArgument(1), states));
    }

    private void whenDebtorSays(Map<LocalDate, PeriodState> states) {
        when(periodPreflight.classifyDates(eq(DEBTOR), any())).thenAnswer(a -> answer(a.getArgument(1), states));
    }

    private static Map<LocalDate, PeriodState> answer(Collection<LocalDate> asked, Map<LocalDate, PeriodState> states) {
        Map<LocalDate, PeriodState> out = new LinkedHashMap<>();
        asked.forEach(d -> out.put(d, states.getOrDefault(d, PeriodState.UNKNOWN)));
        return out;
    }

    private static Map<LocalDate, PeriodState> allUnknown(Collection<LocalDate> asked) {
        return answer(asked, Map.of());
    }
}
