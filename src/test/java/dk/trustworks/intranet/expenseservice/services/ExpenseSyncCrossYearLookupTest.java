package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.8 — the cross-year voucher-number lookup, and the 404 handling it depends on.
 *
 * <p>Added after the 2026-09-11 investigation of a deletion circuit breaker that had been tripped
 * for four consecutive nights on 36 expenses (34 of them already reimbursed). None of the 36 was
 * deleted in e-conomic. Their vouchers were sitting in the ledger under a DIFFERENT accounting year
 * than the one stored on the row: the upload persists the year it SENT, while e-conomic files the
 * entry by its DATE, and the accountant re-dates uploads back into the still-open prior fiscal year
 * around the 1 July boundary. Every recovery path that spans years matched on the "#uuid8" voucher
 * text marker, which none of these rows can carry — they were uploaded before it shipped on
 * 2026-08-05 — so the sync concluded "gone" on rows that were merely filed elsewhere.
 *
 * <p>The fixtures below use the real production shapes: journal 8 / voucher 4000947 / stored year
 * 2026_6_2027 / booked in 2025_6_2026, and the 873b2d85 row whose stored year "2025_6_2026" simply
 * does not exist in its agreement (that tenant's FY2025/26 is named "2025_6_2026a").
 */
@ExtendWith(MockitoExtension.class)
class ExpenseSyncCrossYearLookupTest {

    private static final int VOUCHER = 4000947;
    private static final String JOURNAL_FILTER = "voucher.voucherNumber$eq:" + VOUCHER;
    private static final String YEAR_FILTER = "voucherNumber$eq:" + VOUCHER;
    private static final String EMPTY = "{\"collection\":[]}";
    private static final String JOURNALS_LISTING =
            "{\"collection\":[{\"journalNumber\":8},{\"journalNumber\":19}]}";

    /** As prod returns it: the prior fiscal year is already CLOSED, which is where the voucher is. */
    private static final String YEARS_LISTING =
            "{\"collection\":["
                    + "{\"year\":\"2026/2027\",\"closed\":false,\"toDate\":\"2027-06-30\"},"
                    + "{\"year\":\"2025/2026\",\"closed\":true,\"toDate\":\"2026-06-30\"}]}";

    @Mock
    EconomicsService economicsService;

    @Mock
    ExpenseService expenseService;

    @Mock
    EconomicsAPI api;

    @InjectMocks
    ExpenseSyncBatchlet batchlet;

    EconomicsRetryExecutor retry;
    List<Expense> deletionCandidates;

    @BeforeEach
    void setUp() {
        batchlet.syncDeleteMissThreshold = 3;
        batchlet.syncDeleteAbortThreshold = 20;
        batchlet.syncDeleteAbortPercent = 5.0;
        retry = new EconomicsRetryExecutor(0, millis -> {});
        deletionCandidates = new ArrayList<>();
    }

    // ---- the production case: booked under another year, not deleted -----------------

    @Test
    void voucher_booked_under_a_different_accounting_year_is_rekeyed_instead_of_deleted() {
        Expense expense = expense();
        expense.setSyncMissCount(2); // one more miss would have queued it for deletion
        stubNotFoundUnderTheStoredYear(expense);
        when(api.getYearEntries(eq("2025_6_2026"), eq(YEAR_FILTER), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(preMarkerLedgerEntry(915.75)));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals("2025_6_2026", expense.getAccountingyear(), "the stored year must be corrected to where it is booked");
        assertEquals(VOUCHER, expense.getVouchernumber(), "the voucher NUMBER was never wrong — only the year");
        assertEquals(8, expense.getJournalnumber(), "a booked voucher no longer lives in a journal; leave it alone");
        assertTrue(deletionCandidates.isEmpty(), "an already-reimbursed expense must never be queued for deletion");
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_VERIFIED_BOOKED);
        verify(expenseService).updateSyncMissCount(expense, 0);
    }

    @Test
    void the_closed_prior_year_is_searched_because_that_is_where_bookings_end_up() {
        Expense expense = expense();
        stubNotFoundUnderTheStoredYear(expense);
        when(api.getYearEntries(eq("2025_6_2026"), eq(YEAR_FILTER), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(preMarkerLedgerEntry(915.75)));

        batchlet.syncExpense(expense, retry, deletionCandidates);

        // Booking is the act that PRECEDES closing, so a closed year is exactly where a booked
        // voucher is found. The marker sweep's extractOpenYears would have skipped this one.
        verify(api).getYearEntries(eq("2025_6_2026"), eq(YEAR_FILTER), eq(1000), eq(0));
    }

    // ---- the 873b2d85 case: the stored year does not exist at all ---------------------

    @Test
    void a_stored_year_that_does_not_exist_falls_through_to_the_sweeps_instead_of_erroring() {
        Expense expense = expense();
        expense.setAccountingyear("2025_6_2026"); // the tenant's FY2025/26 is really "2025_6_2026a"
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(8), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        // A 404 reaches the batchlet as a THROWN NotFoundException, never as a Response: the
        // MicroProfile Rest Client's default exception mapper is a ClientResponseFilter, so a
        // Response return type buys no exemption. Before this was handled the row died as ERROR
        // here — 126 lines and six API stages before the miss counter — which is why it sat at
        // sync_miss_count=0 and re-logged "HTTP 404 Not Found" every night for 14 nights.
        when(api.getYearEntries(eq("2025_6_2026"), eq(YEAR_FILTER), eq(1000), eq(0)))
                .thenThrow(new NotFoundException());
        when(api.getJournals(ExpenseSyncBatchlet.JOURNALS_PAGESIZE)).thenAnswer(inv -> ok(JOURNALS_LISTING));
        when(api.getJournalEntries(eq(19), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getJournalEntriesPage(eq(8), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
        when(api.getJournalEntriesPage(eq(19), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
        when(api.getAccountingYears(50)).thenAnswer(inv -> ok(YEARS_LISTING));
        when(api.getYearEntries(eq("2026_6_2027"), eq("amount$eq:915.75"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));
        when(api.getYearEntries(eq("2026_6_2027"), eq(YEAR_FILTER), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(preMarkerLedgerEntry(915.75)));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals("2026_6_2027", expense.getAccountingyear(), "the unusable year label must be replaced by a real one");
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_VERIFIED_BOOKED);
    }

    // ---- guards: e-conomic reuses voucher numbers across years -----------------------

    @Test
    void a_cross_year_number_hit_with_a_different_amount_is_not_a_match() {
        Expense expense = expense();
        stubNotFoundUnderTheStoredYear(expense);
        // Same voucher number, different cost: e-conomic reassigns freed numbers, so the number
        // alone proves nothing (2026-08-31: 6037343 had become an unrelated intercompany invoice).
        when(api.getYearEntries(eq("2025_6_2026"), eq(YEAR_FILTER), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(preMarkerLedgerEntry(4711.0)));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals("2026_6_2027", expense.getAccountingyear(), "must NOT re-key onto someone else's voucher");
        assertTrue(deletionCandidates.isEmpty(), "first miss is still inside the grace period");
        verify(expenseService).updateSyncMissCount(expense, 1);
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
    }

    @Test
    void a_failed_cross_year_lookup_leaves_the_expense_untouched_because_absence_is_unproven() {
        Expense expense = expense();
        stubNotFoundUnderTheStoredYear(expense);
        when(api.getYearEntries(eq("2025_6_2026"), eq(YEAR_FILTER), eq(1000), eq(0)))
                .thenAnswer(inv -> Response.status(500).entity("boom").build());

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.ERROR, outcome);
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
        verify(expenseService, never()).updateSyncMissCount(any(Expense.class), anyInt());
    }

    // ---- the deletion phase must not trust a count thinned by failures ----------------

    @Test
    void undecided_rows_that_could_have_tripped_the_breaker_block_the_whole_batch() {
        // The live shape: a 36-row blocked batch thinned to 20 by transient failures. 20 > 20 is
        // false and 20 > 5% of 757 is false, so without this guard 20 already-reimbursed rows
        // would be deleted — chosen by nothing but which lookups happened to fail that night.
        List<Expense> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) candidates.add(expense());

        batchlet.applyDeletionPhase(candidates, 757, 16);

        verify(expenseService, never()).updateStatus(any(Expense.class), any());
        verify(expenseService, never()).updateSyncMissCount(any(Expense.class), anyInt());
    }

    @Test
    void a_clean_run_still_applies_a_small_legitimate_batch() {
        List<Expense> candidates = new ArrayList<>();
        for (int i = 0; i < 3; i++) candidates.add(expense());

        batchlet.applyDeletionPhase(candidates, 757, 0);

        verify(expenseService, org.mockito.Mockito.times(3)).updateStatus(any(Expense.class), eq(ExpenseService.STATUS_DELETED));
    }

    @Test
    void undecided_rows_that_could_not_have_changed_the_verdict_do_not_block_it() {
        List<Expense> candidates = new ArrayList<>();
        for (int i = 0; i < 3; i++) candidates.add(expense());

        batchlet.applyDeletionPhase(candidates, 757, 2); // 5 is still inside both caps

        verify(expenseService, org.mockito.Mockito.times(3)).updateStatus(any(Expense.class), eq(ExpenseService.STATUS_DELETED));
    }

    // ---- fixtures --------------------------------------------------------------------

    /** Everything up to and including step 2.7 finds nothing — the state all 36 prod rows were in. */
    private void stubNotFoundUnderTheStoredYear(Expense expense) {
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(8), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getYearEntries(eq("2026_6_2027"), eq(YEAR_FILTER), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
        when(api.getJournals(ExpenseSyncBatchlet.JOURNALS_PAGESIZE)).thenAnswer(inv -> ok(JOURNALS_LISTING));
        when(api.getJournalEntries(eq(19), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getJournalEntriesPage(eq(8), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
        when(api.getJournalEntriesPage(eq(19), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
        when(api.getAccountingYears(50)).thenAnswer(inv -> ok(YEARS_LISTING));
        // step 2.7's booked-marker scan: open years only, and it finds nothing because the entry
        // below carries no marker at all.
        when(api.getYearEntries(eq("2026_6_2027"), eq("amount$eq:915.75"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));
    }

    /** A ledger entry as it really looks for these rows: no "#uuid8" marker anywhere in the text. */
    private static String preMarkerLedgerEntry(double amount) {
        return "{\"collection\":[{\"voucherNumber\":" + VOUCHER + ","
                + "\"amount\":" + amount + ","
                + "\"account\":{\"accountNumber\":\"2770\"},"
                + "\"text\":\"Udlæg | Ida Hupfeld | Taxa/tog/bus\"}]}";
    }

    private static Response ok(String body) {
        return Response.status(200).entity(body).build();
    }

    private static Expense expense() {
        Expense expense = new Expense();
        expense.setUuid(UUID.randomUUID().toString());
        expense.setStatus(ExpenseService.STATUS_VERIFIED_UNBOOKED);
        expense.setJournalnumber(8);
        expense.setAccountingyear("2026_6_2027");
        expense.setVouchernumber(VOUCHER);
        expense.setAmount(915.75);
        expense.setExpensedate(LocalDate.of(2026, 6, 24)); // June = the PRIOR fiscal year
        expense.setDatecreated(LocalDate.of(2026, 6, 24));
        expense.setDatemodified(LocalDate.of(2026, 6, 30));
        return expense;
    }
}
