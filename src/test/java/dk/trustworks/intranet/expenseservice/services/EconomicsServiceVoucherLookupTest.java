package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.services.EconomicsService.VoucherLookupResult;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Classification tests for the voucher lookups (2026-08-12 incident): a failed
 * e-conomic call (5xx / network error) must classify as UNKNOWN — never as
 * NOT_FOUND. Treating transient errors as absence is what marked booked vouchers
 * as orphaned and precheck'ed them as MISSING during the e-conomic 503 outage.
 */
@ExtendWith(MockitoExtension.class)
class EconomicsServiceVoucherLookupTest {

    @Mock
    EconomicsAPI api;

    EconomicsService service;

    @BeforeEach
    void setUp() {
        service = new EconomicsService();
        service.verifyRetrySleepMs = 0L; // no eventual-consistency delays in tests
    }

    private Expense expense() {
        Expense e = new Expense();
        e.setUuid("a6187648-dd3c-48b2-8918-34b01896c0a8");
        e.setVouchernumber(4000978);
        e.setJournalnumber(8);
        e.setAccountingyear("2026_6_2027");
        return e;
    }

    // --- checkVoucherExists (draft journal) ---

    @Test
    void draft_2xx_is_found() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenReturn(Response.status(200).build());
        assertEquals(VoucherLookupResult.FOUND, service.checkVoucherExists(expense(), api));
    }

    @Test
    void draft_consistent_404_is_proven_not_found() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenReturn(
                Response.status(404).build(),
                Response.status(404).build(),
                Response.status(404).build());
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherExists(expense(), api));
        verify(api, times(3)).getVoucher(8, "2026_6_2027", 4000978);
    }

    @Test
    void draft_404_then_200_is_found_after_retry() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenReturn(
                Response.status(404).build(),
                Response.status(200).build());
        assertEquals(VoucherLookupResult.FOUND, service.checkVoucherExists(expense(), api));
    }

    @Test
    void draft_5xx_is_unknown_not_absence() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenReturn(Response.status(503).build());
        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherExists(expense(), api));
        verify(api, times(1)).getVoucher(anyInt(), anyString(), anyInt());
    }

    @Test
    void draft_transport_error_is_unknown_not_absence() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenThrow(new RuntimeException(
                "HTTP 503 from Economics: upstream connect error or disconnect/reset before headers"));
        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherExists(expense(), api));
    }

    @Test
    void draft_exception_mapped_404_is_proven_not_found() {
        when(api.getVoucher(8, "2026_6_2027", 4000978))
                .thenThrow(new RuntimeException("HTTP 404 from Economics"));
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherExists(expense(), api));
    }

    @Test
    void draft_missing_triple_is_not_found_without_any_call() {
        Expense e = new Expense();
        e.setUuid("no-voucher");
        e.setVouchernumber(0);
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherExists(e));
    }

    // --- checkVoucherBooked (accounting-year ledger) ---

    @Test
    void booked_entries_present_is_found() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(200).entity("{\"collection\":[{\"voucherNumber\":4000978}]}").build());
        assertEquals(VoucherLookupResult.FOUND, service.checkVoucherBooked(expense(), api));
    }

    @Test
    void booked_empty_collection_is_proven_not_found() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(200).entity("{\"collection\":[]}").build());
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherBooked(expense(), api));
    }

    @Test
    void booked_404_year_not_addressable_is_not_found() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(404).entity("no such year").build());
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherBooked(expense(), api));
    }

    @Test
    void booked_5xx_is_unknown_not_absence() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(500).entity("boom").build());
        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherBooked(expense(), api));
    }

    @Test
    void booked_transport_error_is_unknown_not_absence() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenThrow(new RuntimeException("connection reset"));
        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherBooked(expense(), api));
    }

    // --- checkVoucherBooked with an incomplete triple ---
    //
    // 2026-08-07: legacy rows carry a voucher number but no journal/accountingyear. Requiring the
    // full triple short-circuited them to NOT_FOUND without ever calling e-conomic, so the re-send
    // precheck reported "MISSING" for vouchers that were booked — and the duplicate warning that
    // should have fired never did.

    private static final String YEARS_BODY = """
            {"collection":[
              {"year":"2023/2024","toDate":"2024-06-30","closed":true},
              {"year":"2024/2025","toDate":"2025-06-30","closed":true},
              {"year":"2025/2026a","toDate":"2026-06-30"},
              {"year":"2026/2027","toDate":"2027-06-30"}
            ]}""";

    private Expense expenseWithoutYear() {
        Expense e = new Expense();
        e.setUuid("ed140b94-38eb-42a8-9fdd-c7be4d1b2cb4");
        e.setVouchernumber(6018626);
        e.setExpensedate(LocalDate.of(2024, 12, 7));
        return e;   // no journalnumber, no accountingyear — the legacy shape
    }

    @Test
    void booked_no_journal_still_queries_the_ledger() {
        // The booked ledger is addressed by year alone; the journal number is irrelevant to it.
        Expense e = expense();
        e.setJournalnumber(null);
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(200).entity("{\"collection\":[{\"voucherNumber\":4000978}]}").build());
        assertEquals(VoucherLookupResult.FOUND, service.checkVoucherBooked(e, api));
    }

    @Test
    void booked_no_year_searches_candidate_years_and_finds_it() {
        when(api.getAccountingYears(50)).thenReturn(Response.status(200).entity(YEARS_BODY).build());
        when(api.getYearEntries("2024_6_2025", "voucherNumber$eq:6018626", 1000, 0))
                .thenReturn(Response.status(200).entity("{\"collection\":[{\"voucherNumber\":6018626}]}").build());

        assertEquals(VoucherLookupResult.FOUND, service.checkVoucherBooked(expenseWithoutYear(), api));
        // 2023/2024 ended before the expense date — nothing could have been booked there.
        verify(api, never()).getYearEntries(eq("2023_6_2024"), anyString(), anyInt(), anyInt());
    }

    @Test
    void booked_no_year_all_candidates_empty_is_proven_not_found() {
        when(api.getAccountingYears(50)).thenReturn(Response.status(200).entity(YEARS_BODY).build());
        when(api.getYearEntries(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Response.status(200).entity("{\"collection\":[]}").build());

        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherBooked(expenseWithoutYear(), api));
    }

    @Test
    void booked_no_year_one_failing_candidate_is_unknown_not_absence() {
        when(api.getAccountingYears(50)).thenReturn(Response.status(200).entity(YEARS_BODY).build());
        when(api.getYearEntries(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Response.status(200).entity("{\"collection\":[]}").build(),
                            Response.status(503).entity("boom").build(),
                            Response.status(200).entity("{\"collection\":[]}").build());

        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherBooked(expenseWithoutYear(), api));
    }

    @Test
    void booked_no_year_failed_years_listing_is_unknown_not_absence() {
        when(api.getAccountingYears(50)).thenReturn(Response.status(500).entity("boom").build());

        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherBooked(expenseWithoutYear(), api));
        verify(api, never()).getYearEntries(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void booked_without_voucher_number_needs_no_call_at_all() {
        Expense e = new Expense();
        e.setUuid("never-posted");
        e.setVouchernumber(0);
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherBooked(e));
    }

    // --- candidateBookedYears (pure) ---

    @Test
    void candidateYears_drops_years_that_ended_before_the_expense() {
        assertEquals(List.of("2025/2026a", "2026/2027"),
                EconomicsService.candidateBookedYears(YEARS_BODY, LocalDate.of(2025, 8, 1)));
    }

    @Test
    void candidateYears_keeps_everything_when_the_expense_date_is_unknown() {
        assertEquals(4, EconomicsService.candidateBookedYears(YEARS_BODY, null).size());
    }

    @Test
    void candidateYears_keeps_a_year_with_an_unusable_toDate() {
        // Better one extra GET than a missed booking.
        assertEquals(List.of("2025/2026a"), EconomicsService.candidateBookedYears(
                "{\"collection\":[{\"year\":\"2025/2026a\",\"toDate\":\"\"}]}", LocalDate.of(2026, 1, 1)));
    }

    @Test
    void candidateYears_of_junk_is_empty_not_an_exception() {
        assertEquals(List.of(), EconomicsService.candidateBookedYears("not json", LocalDate.of(2026, 1, 1)));
        assertEquals(List.of(), EconomicsService.candidateBookedYears(null, null));
    }
}
