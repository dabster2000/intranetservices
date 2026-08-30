package dk.trustworks.intranet.aggregates.invoice.economics.period;

import dk.trustworks.intranet.aggregates.invoice.economics.period.AccountingPeriodPreflight.PeriodState;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.model.Company;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountingPeriodPreflight}.
 *
 * <p>Three properties matter and they pull against each other. It must catch the 2026-08-27 shape —
 * a period that is Åben but Spærret, which the legacy API's single {@code closed} flag cannot see.
 * It must never block on anything short of proof, because it sits in front of every finalization in
 * all three companies and a false block is a self-inflicted outage. And OPEN must be distinguishable
 * from UNKNOWN, because the nightly job back-dates on OPEN and would otherwise back-date into a
 * period nobody confirmed.
 */
@ExtendWith(MockitoExtension.class)
class AccountingPeriodPreflightTest {

    @InjectMocks AccountingPeriodPreflight preflight;

    @Mock EconomicsAccountingYearsApiClient periodsApi;
    @Mock EconomicsAgreementResolver agreements;

    private static final LocalDate AUG_27 = LocalDate.of(2026, 8, 27);
    private static final LocalDate JUN_30 = LocalDate.of(2026, 6, 30);

    // ── the pure classification ─────────────────────────────────────────────────────────────

    /** Exactly the TWC state: FY 2026/27 open for business, every period barred. */
    @Test
    void a_barred_period_blocks_even_though_it_is_not_closed() {
        EconomicsAccountingPeriod august = barred("2026/2027", "2026-08-01", "2026-08-31");

        AccountingPeriodPreflight.Verdict verdict =
                AccountingPeriodPreflight.classify(List.of(august), AUG_27);

        assertEquals(PeriodState.BLOCKED, verdict.state(),
                "isBarred alone must block — this is the whole reason for the new API");
        assertEquals("barred", verdict.period().blockReason());
    }

    @Test
    void a_closed_period_blocks_too() {
        assertEquals(PeriodState.BLOCKED, stateOf(
                List.of(period("2024/2025", "2025-06-01", "2025-06-30", true, false)),
                LocalDate.of(2025, 6, 15)));
    }

    /** TWC's FY 2025/26: every period Åben, nothing barred. This is where the five must book. */
    @Test
    void an_open_period_reads_OPEN_not_merely_not_blocked() {
        assertEquals(PeriodState.OPEN, stateOf(
                List.of(period("2025/2026", "2026-06-01", "2026-06-30", false, false)), JUN_30),
                "the nightly job back-dates only on OPEN, so this must not collapse into UNKNOWN");
    }

    @Test
    void bounds_are_inclusive() {
        List<EconomicsAccountingPeriod> june = List.of(barred("2025/2026", "2026-06-01", "2026-06-30"));

        assertEquals(PeriodState.BLOCKED, stateOf(june, LocalDate.of(2026, 6, 1)), "first day");
        assertEquals(PeriodState.BLOCKED, stateOf(june, JUN_30), "last day");
        assertEquals(PeriodState.UNKNOWN, stateOf(june, LocalDate.of(2026, 7, 1)), "day after");
    }

    /** The barred period next door must not condemn a date that falls in an open one. */
    @Test
    void only_the_covering_period_counts() {
        List<EconomicsAccountingPeriod> periods = List.of(
                period("2025/2026", "2026-06-01", "2026-06-30", false, false),
                barred("2026/2027", "2026-07-01", "2026-07-31"),
                barred("2026/2027", "2026-08-01", "2026-08-31"));

        assertEquals(PeriodState.OPEN, stateOf(periods, JUN_30));
        assertEquals(PeriodState.BLOCKED, stateOf(periods, AUG_27));
    }

    /**
     * A date in no period at all is outside every accounting year and e-conomic would refuse it,
     * but that is indistinguishable from a short read, so it must not block.
     */
    @Test
    void a_date_covered_by_no_period_is_UNKNOWN() {
        assertEquals(PeriodState.UNKNOWN, stateOf(
                List.of(barred("2025/2026", "2026-06-01", "2026-06-30")),
                LocalDate.of(2031, 1, 1)));
    }

    @Test
    void unparseable_or_missing_bounds_are_UNKNOWN() {
        assertEquals(PeriodState.UNKNOWN,
                stateOf(List.of(barred("2026/2027", "not-a-date", "2026-08-31")), AUG_27));
        assertEquals(PeriodState.UNKNOWN,
                stateOf(List.of(barred("2026/2027", null, null)), AUG_27));
    }

    /** The vendor documents these as date-times; real agreements send plain dates. Accept both. */
    @Test
    void date_time_bounds_are_accepted() {
        assertEquals(PeriodState.BLOCKED, stateOf(
                List.of(barred("2026/2027", "2026-08-01T00:00:00Z", "2026-08-31T00:00:00Z")),
                AUG_27));
    }

    @Test
    void empty_and_null_inputs_are_UNKNOWN() {
        assertEquals(PeriodState.UNKNOWN, stateOf(List.of(), AUG_27));
        assertEquals(PeriodState.UNKNOWN, stateOf(null, AUG_27));
        assertEquals(PeriodState.UNKNOWN,
                stateOf(List.of(barred("2026/2027", "2026-08-01", "2026-08-31")), null));
    }

    // ── the guard ───────────────────────────────────────────────────────────────────────────

    @Test
    void assertPeriodOpen_throws_naming_the_company_date_and_remedies() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(barred("2026/2027", "2026-08-01", "2026-08-31"));

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> preflight.assertPeriodOpen(invoice("Trustworks Cyber Security ApS", AUG_27)));

        String msg = thrown.getMessage();
        assertTrue(msg.contains("Trustworks Cyber Security ApS"), msg);
        assertTrue(msg.contains("2026-08-27"), msg);
        assertTrue(msg.contains("barred"), msg);
        assertTrue(msg.contains("Nothing was sent to e-conomic"),
                "must state that no draft exists, or the operator cannot tell this from the "
                        + "post-booking failure: " + msg);
    }

    @Test
    void assertPeriodOpen_passes_for_an_open_period() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(period("2025/2026", "2026-06-01", "2026-06-30", false, false));

        assertDoesNotThrow(() ->
                preflight.assertPeriodOpen(invoice("Trustworks Cyber Security ApS", JUN_30)));
    }

    /** A vendor outage must not become a Trustworks-wide invoicing outage. */
    @Test
    void assertPeriodOpen_never_blocks_when_the_vendor_call_fails() {
        preflight.preflightEnabled = true;
        whenPeriodsThrow(new WebApplicationException("e-conomic unavailable",
                Response.status(503).build()));

        assertDoesNotThrow(() ->
                preflight.assertPeriodOpen(invoice("Trustworks Cyber Security ApS", AUG_27)));
    }

    /** A missing or misconfigured agreement token is a config problem, not a reason to refuse. */
    @Test
    void assertPeriodOpen_never_blocks_on_a_missing_agreement_token() {
        preflight.preflightEnabled = true;
        when(agreements.tokens(anyString())).thenThrow(new IllegalStateException("Company not found"));

        assertDoesNotThrow(() ->
                preflight.assertPeriodOpen(invoice("Trustworks Cyber Security ApS", AUG_27)));
    }

    @Test
    void the_kill_switch_skips_the_vendor_entirely() {
        preflight.preflightEnabled = false;

        assertDoesNotThrow(() ->
                preflight.assertPeriodOpen(invoice("Trustworks Cyber Security ApS", AUG_27)));
        assertFalse(preflight.isKnownOpen("company-uuid", JUN_30),
                "disabled must also stop back-dating, not just stop blocking");
        verifyNoInteractions(periodsApi, agreements);
    }

    @Test
    void an_invoice_with_no_date_or_no_company_is_skipped() {
        preflight.preflightEnabled = true;

        Invoice noCompany = new Invoice();
        noCompany.setInvoicedate(AUG_27);

        assertDoesNotThrow(() -> preflight.assertPeriodOpen(invoice("Trustworks A/S", null)));
        assertDoesNotThrow(() -> preflight.assertPeriodOpen(noCompany));
        assertDoesNotThrow(() -> preflight.assertPeriodOpen(null));
        verifyNoInteractions(periodsApi, agreements);
    }

    // ── the date chooser's view ─────────────────────────────────────────────────────────────

    @Test
    void isKnownOpen_is_true_only_for_a_confirmed_open_period() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(period("2025/2026", "2026-06-01", "2026-06-30", false, false));

        assertTrue(preflight.isKnownOpen("company-uuid", JUN_30));
    }

    @Test
    void isKnownOpen_is_false_for_a_barred_period() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(barred("2026/2027", "2026-08-01", "2026-08-31"));

        assertFalse(preflight.isKnownOpen("company-uuid", AUG_27));
    }

    /**
     * The asymmetry that makes both callers safe: the guard treats UNKNOWN as "carry on", the date
     * chooser treats it as "don't back-date". A vendor outage must never move an accounting period.
     */
    @Test
    void isKnownOpen_is_false_when_the_vendor_cannot_be_reached() {
        preflight.preflightEnabled = true;
        whenPeriodsThrow(new WebApplicationException("e-conomic unavailable",
                Response.status(503).build()));

        assertFalse(preflight.isKnownOpen("company-uuid", JUN_30));
    }

    @Test
    void isKnownOpen_is_false_when_no_period_covers_the_date() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(period("2025/2026", "2026-06-01", "2026-06-30", false, false));

        assertFalse(preflight.isKnownOpen("company-uuid", LocalDate.of(2031, 1, 1)));
    }

    @Test
    void isKnownOpen_is_false_for_null_inputs() {
        preflight.preflightEnabled = true;

        assertFalse(preflight.isKnownOpen(null, JUN_30));
        assertFalse(preflight.isKnownOpen("company-uuid", null));
        verifyNoInteractions(periodsApi, agreements);
    }

    // ── the expense voucher's view: is walking the date forward pointless? ───────────────────

    /** The eight entry dates EconomicsService.sendVoucher would try, starting 2026-08-30. */
    private static final List<LocalDate> AUG_30_PLUS_7 = List.of(
            LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3),
            LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 6));

    /** The live TWC case: every period of FY 2026/27 barred, so no shift can escape it. */
    @Test
    void allDatesBlocked_is_true_when_every_date_lands_in_a_barred_period() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(
                barred("2026/2027", "2026-08-01", "2026-08-31"),
                barred("2026/2027", "2026-09-01", "2026-09-30"));

        assertTrue(preflight.allDatesBlocked("company-uuid", AUG_30_PLUS_7),
                "a wholly barred year makes all eight POSTs pointless");
    }

    /**
     * The case that must never be swallowed. A barred August inside an open September is exactly
     * what the auto-shift loop exists to escape: refusing here on the strength of the first date
     * would delete the loop's only working outcome and strand an expense that would have posted.
     */
    @Test
    void allDatesBlocked_is_false_when_a_later_date_escapes_into_an_open_period() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(
                barred("2026/2027", "2026-08-01", "2026-08-31"),
                period("2026/2027", "2026-09-01", "2026-09-30", false, false));

        assertFalse(preflight.allDatesBlocked("company-uuid", AUG_30_PLUS_7),
                "the shift into September clears the bar — the loop must still run");
    }

    /** An unreadable period is not a blocked one; UNKNOWN anywhere in the range falls open. */
    @Test
    void allDatesBlocked_is_false_when_any_date_is_covered_by_no_period() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(barred("2026/2027", "2026-08-01", "2026-08-31"));

        assertFalse(preflight.allDatesBlocked("company-uuid", AUG_30_PLUS_7),
                "September is covered by no period at all — that is UNKNOWN, not BLOCKED");
    }

    /** A vendor outage must not stop expenses e-conomic would have accepted. */
    @Test
    void allDatesBlocked_is_false_when_the_vendor_cannot_be_reached() {
        preflight.preflightEnabled = true;
        whenPeriodsThrow(new WebApplicationException("e-conomic unavailable",
                Response.status(503).build()));

        assertFalse(preflight.allDatesBlocked("company-uuid", AUG_30_PLUS_7));
    }

    @Test
    void allDatesBlocked_is_false_on_the_kill_switch_and_on_empty_input() {
        preflight.preflightEnabled = false;
        assertFalse(preflight.allDatesBlocked("company-uuid", AUG_30_PLUS_7));

        preflight.preflightEnabled = true;
        assertFalse(preflight.allDatesBlocked("company-uuid", List.of()));
        assertFalse(preflight.allDatesBlocked("company-uuid", null));
        assertFalse(preflight.allDatesBlocked(null, AUG_30_PLUS_7));

        verifyNoInteractions(periodsApi, agreements);
    }

    /** The point of the whole exercise: eight dates must still cost one vendor read, not eight. */
    @Test
    void allDatesBlocked_reads_the_agreement_once_however_many_dates_it_is_given() {
        preflight.preflightEnabled = true;
        whenPeriodsReturn(
                barred("2026/2027", "2026-08-01", "2026-08-31"),
                barred("2026/2027", "2026-09-01", "2026-09-30"));

        preflight.allDatesBlocked("company-uuid", AUG_30_PLUS_7);

        verify(periodsApi, times(1)).listPeriods(anyString(), anyString(), anyInt(), anyInt());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static PeriodState stateOf(List<EconomicsAccountingPeriod> periods, LocalDate date) {
        return AccountingPeriodPreflight.classify(periods, date).state();
    }

    private void whenPeriodsReturn(EconomicsAccountingPeriod... periods) {
        when(agreements.tokens(anyString()))
                .thenReturn(new EconomicsAgreementResolver.Tokens("secret", "grant"));
        when(periodsApi.listPeriods(anyString(), anyString(), anyInt(), eq(0)))
                .thenReturn(List.of(periods));
    }

    private void whenPeriodsThrow(RuntimeException failure) {
        when(agreements.tokens(anyString()))
                .thenReturn(new EconomicsAgreementResolver.Tokens("secret", "grant"));
        when(periodsApi.listPeriods(anyString(), anyString(), anyInt(), anyInt())).thenThrow(failure);
    }

    private static EconomicsAccountingPeriod barred(String year, String from, String to) {
        return period(year, from, to, false, true);
    }

    private static EconomicsAccountingPeriod period(String year, String from, String to,
                                                    boolean closed, boolean isBarred) {
        EconomicsAccountingPeriod p = new EconomicsAccountingPeriod();
        p.setPeriodNumber(1);
        p.setYear(year);
        p.setDateFrom(from);
        p.setDateTo(to);
        p.setIsClosed(closed);
        p.setIsBarred(isBarred);
        return p;
    }

    private static Invoice invoice(String companyName, LocalDate invoicedate) {
        Company company = new Company();
        company.setUuid("company-uuid");
        company.setName(companyName);
        Invoice inv = new Invoice();
        inv.setUuid("invoice-uuid");
        inv.setCompany(company);
        inv.setInvoicedate(invoicedate);
        return inv;
    }
}
