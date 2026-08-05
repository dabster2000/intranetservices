package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Deletion-safety net for the nightly expense sync, added after the 2026-07-28
 * incident (224 still-valid expenses mass-marked DELETED after the accountant
 * moved unbooked vouchers into temporary year-end journals):
 *
 * <ol>
 *   <li>a voucher found in a NON-stored journal is never deleted — the stored
 *       journalnumber self-heals and the expense stays VERIFIED_UNBOOKED;</li>
 *   <li>a "not found anywhere" outcome only deletes after N consecutive misses
 *       (grace period), and a reappearance resets the counter;</li>
 *   <li>deletions are deferred to an end-of-run phase that applies NOTHING when
 *       the batch exceeds the circuit-breaker caps ("0 deleted + 1 alert").</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ExpenseSyncDeletionSafetyTest {

    private static final String JOURNAL_FILTER = "voucher.voucherNumber$eq:12345";
    private static final String EMPTY = "{\"collection\":[]}";
    private static final String JOURNALS_LISTING =
            "{\"collection\":[{\"journalNumber\":16},{\"journalNumber\":42}]}";
    private static final String SWEEP_HIT =
            "{\"collection\":[{\"account\":{\"accountNumber\":\"5820\"},\"text\":\"flyttet af revisor\"}]}";

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

    // ---- #1: all-journals sweep ------------------------------------------------

    @Test
    void voucher_moved_to_other_journal_is_not_deleted_and_journalnumber_self_heals() {
        Expense expense = expense();
        expense.setSyncMissCount(2); // pending misses from previous nights
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getYearEntries(eq("2025_6_2026"), eq("voucherNumber$eq:12345"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));
        when(api.getJournals(ExpenseSyncBatchlet.JOURNALS_PAGESIZE)).thenAnswer(inv -> ok(JOURNALS_LISTING));
        when(api.getJournalEntries(eq(42), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(SWEEP_HIT));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals(42, expense.getJournalnumber(), "stored journalnumber must self-heal to where the voucher was found");
        assertEquals("5820", expense.getAccount());
        assertEquals("flyttet af revisor", expense.getAccountantNotes());
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_VERIFIED_UNBOOKED);
        verify(expenseService, never()).updateStatus(any(Expense.class), eq(ExpenseService.STATUS_DELETED));
        verify(expenseService).updateSyncMissCount(expense, 0);
    }

    @Test
    void missing_stored_journal_404_falls_through_to_sweep_instead_of_erroring() {
        Expense expense = expense();
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000)))
                .thenAnswer(inv -> Response.status(404).entity("journal gone").build());
        when(api.getYearEntries(eq("2025_6_2026"), eq("voucherNumber$eq:12345"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));
        when(api.getJournals(ExpenseSyncBatchlet.JOURNALS_PAGESIZE)).thenAnswer(inv -> ok(JOURNALS_LISTING));
        when(api.getJournalEntries(eq(42), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(SWEEP_HIT));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals(42, expense.getJournalnumber());
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_VERIFIED_UNBOOKED);
        verify(expenseService, never()).updateStatus(any(Expense.class), eq(ExpenseService.STATUS_DELETED));
    }

    @Test
    void failed_sweep_lookup_leaves_expense_unchanged_instead_of_deleting() {
        Expense expense = expense();
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getYearEntries(eq("2025_6_2026"), eq("voucherNumber$eq:12345"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));
        when(api.getJournals(ExpenseSyncBatchlet.JOURNALS_PAGESIZE))
                .thenAnswer(inv -> Response.status(500).entity("boom").build());

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.ERROR, outcome);
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
        verify(expenseService, never()).updateSyncMissCount(any(Expense.class), anyInt());
    }

    // ---- #2: deletion grace period ---------------------------------------------

    @Test
    void first_miss_increments_counter_without_deleting() {
        Expense expense = expense(); // syncMissCount defaults to 0
        stubNotFoundAnywhere(expense);

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService).updateSyncMissCount(expense, 1);
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
    }

    @Test
    void second_miss_increments_counter_without_deleting() {
        Expense expense = expense();
        expense.setSyncMissCount(1);
        stubNotFoundAnywhere(expense);

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService).updateSyncMissCount(expense, 2);
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
    }

    @Test
    void third_consecutive_miss_queues_for_deletion_and_deletion_phase_applies_it() {
        Expense expense = expense();
        expense.setSyncMissCount(2);
        stubNotFoundAnywhere(expense);

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals(List.of(expense), deletionCandidates);

        batchlet.applyDeletionPhase(deletionCandidates, 100);

        verify(expenseService).updateSyncMissCount(expense, 0);
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_DELETED);
    }

    @Test
    void reappearance_in_stored_journal_resets_the_miss_counter() {
        Expense expense = expense();
        expense.setSyncMissCount(2);
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(SWEEP_HIT));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService).updateSyncMissCount(expense, 0);
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_VERIFIED_UNBOOKED);
        verify(expenseService, never()).updateStatus(any(Expense.class), eq(ExpenseService.STATUS_DELETED));
    }

    @Test
    void already_deleted_row_skips_sweep_counter_and_deletion_queue() {
        Expense expense = expense();
        expense.setStatus(ExpenseService.STATUS_DELETED);
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getYearEntries(eq("2025_6_2026"), eq("voucherNumber$eq:12345"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertTrue(deletionCandidates.isEmpty());
        verify(api, never()).getJournals(anyInt());
        verify(expenseService, never()).updateSyncMissCount(any(Expense.class), anyInt());
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
    }

    @Test
    void already_deleted_row_is_resurrected_when_voucher_reappears_in_stored_journal() {
        Expense expense = expense();
        expense.setStatus(ExpenseService.STATUS_DELETED);
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(SWEEP_HIT));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_VERIFIED_UNBOOKED);
    }

    // ---- marker re-link (renumbered by a journal move) ---------------------------

    @Test
    void renumbered_voucher_is_relinked_by_text_marker() {
        Expense expense = expense();
        expense.setAmount(345.0);
        expense.setSyncMissCount(2);
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getYearEntries(eq("2025_6_2026"), eq("voucherNumber$eq:12345"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));
        when(api.getJournals(ExpenseSyncBatchlet.JOURNALS_PAGESIZE)).thenAnswer(inv -> ok(JOURNALS_LISTING));
        when(api.getJournalEntries(eq(42), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getJournalEntriesPage(eq(16), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
        when(api.getJournalEntriesPage(eq(42), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(markerEntry(expense, 777, 345.0)));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals(42, expense.getJournalnumber(), "journal must be re-keyed to where the marker was found");
        assertEquals(777, expense.getVouchernumber(), "voucher number must be re-keyed to the entry's new number");
        assertEquals("Udlæg | Thea Bech | Taxa", expense.getAccountantNotes(), "marker must be stripped from notes");
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_VERIFIED_UNBOOKED);
        verify(expenseService).updateSyncMissCount(expense, 0);
        verify(expenseService, never()).updateStatus(any(Expense.class), eq(ExpenseService.STATUS_DELETED));
    }

    @Test
    void marker_hit_with_wrong_amount_is_not_relinked_and_falls_back_to_grace() {
        Expense expense = expense();
        expense.setAmount(345.0);
        stubNumberLookupsNotFound(expense);
        when(api.getJournalEntriesPage(eq(16), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
        // journal 42's marker page: marker present but amount differs
        when(api.getJournalEntriesPage(eq(42), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(markerEntry(expense, 777, 999.0)));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals(16, expense.getJournalnumber(), "must NOT re-key on an amount mismatch");
        assertEquals(12345, expense.getVouchernumber());
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService).updateSyncMissCount(expense, 1);
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
    }

    @Test
    void ambiguous_marker_hits_leave_expense_unchanged_for_manual_review() {
        Expense expense = expense();
        expense.setAmount(345.0);
        stubNumberLookupsNotFound(expense);
        // the marker appears in BOTH journals — cannot re-link safely
        when(api.getJournalEntriesPage(eq(16), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(markerEntry(expense, 555, 345.0)));
        when(api.getJournalEntriesPage(eq(42), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(markerEntry(expense, 777, 345.0)));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.ERROR, outcome);
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
        verify(expenseService, never()).updateSyncMissCount(any(Expense.class), anyInt());
    }

    // ---- booked-ledger marker search (moved, renumbered AND booked) ---------------

    private static final String YEARS_LISTING =
            "{\"collection\":[{\"year\":\"2025/2026\",\"closed\":false},{\"year\":\"2024/2025\",\"closed\":true}]}";

    @Test
    void voucher_booked_under_new_number_is_relinked_via_ledger_marker() {
        Expense expense = expense();
        expense.setAmount(40.5);
        expense.setSyncMissCount(2);
        stubNotFoundAnywhere(expense);
        when(api.getAccountingYears(50)).thenAnswer(inv -> ok(YEARS_LISTING));
        when(api.getYearEntries(eq("2025_6_2026"), eq("amount$eq:40.5"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(bookedEntry(expense, 6038697, 40.5)));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        assertEquals(6038697, expense.getVouchernumber(), "voucher number must be re-keyed to the booked number");
        assertEquals("2025_6_2026", expense.getAccountingyear(), "year must follow where the entry was actually booked");
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService).updateStatus(expense, ExpenseService.STATUS_VERIFIED_BOOKED);
        verify(expenseService).updateSyncMissCount(expense, 0);
        verify(api, never()).getYearEntries(eq("2024_6_2025"), any(), eq(1000), eq(0)); // closed year skipped
    }

    @Test
    void ambiguous_booked_marker_hits_leave_expense_unchanged() {
        Expense expense = expense();
        expense.setAmount(40.5);
        stubNotFoundAnywhere(expense);
        when(api.getAccountingYears(50)).thenAnswer(inv -> ok(YEARS_LISTING));
        String twoHits = "{\"collection\":["
                + "{\"voucherNumber\":111,\"amount\":40.5,\"text\":\"Udlæg | A | x " + VoucherText.markerFor(expense.getUuid()) + "\"},"
                + "{\"voucherNumber\":222,\"amount\":40.5,\"text\":\"Udlæg | B | y " + VoucherText.markerFor(expense.getUuid()) + "\"}]}";
        when(api.getYearEntries(eq("2025_6_2026"), eq("amount$eq:40.5"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(twoHits));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.ERROR, outcome);
        assertTrue(deletionCandidates.isEmpty());
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
        verify(expenseService, never()).updateSyncMissCount(any(Expense.class), anyInt());
    }

    @Test
    void failed_years_listing_leaves_expense_unchanged() {
        Expense expense = expense();
        expense.setAmount(40.5);
        stubNotFoundAnywhere(expense);
        when(api.getAccountingYears(50)).thenAnswer(inv -> Response.status(500).entity("boom").build());

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.ERROR, outcome);
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
        verify(expenseService, never()).updateSyncMissCount(any(Expense.class), anyInt());
    }

    @Test
    void no_marker_in_ledger_either_falls_through_to_grace() {
        Expense expense = expense();
        expense.setAmount(40.5);
        stubNotFoundAnywhere(expense);
        when(api.getAccountingYears(50)).thenAnswer(inv -> ok(YEARS_LISTING));
        when(api.getYearEntries(eq("2025_6_2026"), eq("amount$eq:40.5"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry, deletionCandidates);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.SUCCESS, outcome);
        verify(expenseService).updateSyncMissCount(expense, 1);
        verify(expenseService, never()).updateStatus(any(Expense.class), any());
    }

    @Test
    void open_year_extraction_and_amount_formatting() {
        assertEquals(java.util.List.of("2025/2026"), ExpenseSyncBatchlet.extractOpenYears(YEARS_LISTING));
        assertEquals("40.5", ExpenseSyncBatchlet.formatAmount(40.5));
        assertEquals("570", ExpenseSyncBatchlet.formatAmount(570.0));
        assertEquals("1017", ExpenseSyncBatchlet.formatAmount(1017.0));
    }

    // ---- #3: mass-deletion circuit breaker ---------------------------------------

    @Test
    void deletion_phase_exceeding_the_cap_applies_no_deletions_at_all() {
        batchlet.syncDeleteAbortThreshold = 2;
        batchlet.syncDeleteAbortPercent = 0.0;
        List<Expense> candidates = List.of(expense(), expense(), expense());

        batchlet.applyDeletionPhase(new ArrayList<>(candidates), 100);

        verifyNoInteractions(expenseService); // "0 deleted + 1 alert": every row untouched
    }

    @Test
    void deletion_phase_within_the_cap_deletes_and_resets_counters() {
        batchlet.syncDeleteAbortThreshold = 2;
        batchlet.syncDeleteAbortPercent = 0.0;
        Expense first = expense();
        Expense second = expense();

        batchlet.applyDeletionPhase(new ArrayList<>(List.of(first, second)), 100);

        verify(expenseService).updateSyncMissCount(first, 0);
        verify(expenseService).updateStatus(first, ExpenseService.STATUS_DELETED);
        verify(expenseService).updateSyncMissCount(second, 0);
        verify(expenseService).updateStatus(second, ExpenseService.STATUS_DELETED);
    }

    @Test
    void deletion_phase_percent_cap_blocks_mass_deletion_even_under_absolute_cap() {
        batchlet.syncDeleteAbortThreshold = 1000;
        batchlet.syncDeleteAbortPercent = 5.0;
        // 30 candidates of 537 selected ≈ 5.6% > 5% → nothing deleted (incident-shaped night)
        List<Expense> candidates = new ArrayList<>();
        for (int i = 0; i < 30; i++) candidates.add(expense());

        batchlet.applyDeletionPhase(candidates, 537);

        verifyNoInteractions(expenseService);
    }

    // ---- helpers -----------------------------------------------------------------

    /** Number-based lookups all miss: stored journal, accounting year, and the number sweep. */
    private void stubNumberLookupsNotFound(Expense expense) {
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getYearEntries(eq("2025_6_2026"), eq("voucherNumber$eq:12345"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));
        when(api.getJournals(ExpenseSyncBatchlet.JOURNALS_PAGESIZE)).thenAnswer(inv -> ok(JOURNALS_LISTING));
        when(api.getJournalEntries(eq(42), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
    }

    /** Voucher absent from the stored journal, the accounting year, and every swept journal. */
    private void stubNotFoundAnywhere(Expense expense) {
        stubNumberLookupsNotFound(expense);
        // marker sweep pages (no marker anywhere)
        when(api.getJournalEntriesPage(eq(16), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
        when(api.getJournalEntriesPage(eq(42), eq(1000), eq(0))).thenAnswer(inv -> ok(EMPTY));
    }

    /** A BOOKED year entry (voucherNumber top-level) carrying the expense's own text marker. */
    private static String bookedEntry(Expense expense, int voucherNumber, double amount) {
        return "{\"collection\":[{\"voucherNumber\":" + voucherNumber + ","
                + "\"amount\":" + amount + ","
                + "\"account\":{\"accountNumber\":\"5820\"},"
                + "\"text\":\"Udlæg | Alberte Bang | Frokost " + VoucherText.markerFor(expense.getUuid()) + "\"}]}";
    }

    /** A journal entry carrying the expense's own text marker. */
    private static String markerEntry(Expense expense, int voucherNumber, double amount) {
        return "{\"collection\":[{\"voucher\":{\"voucherNumber\":" + voucherNumber + "},"
                + "\"amount\":" + amount + ","
                + "\"account\":{\"accountNumber\":\"5820\"},"
                + "\"text\":\"Udlæg | Thea Bech | Taxa " + VoucherText.markerFor(expense.getUuid()) + "\"}]}";
    }

    private static Response ok(String body) {
        return Response.status(200).entity(body).build();
    }

    private static Expense expense() {
        Expense expense = new Expense();
        expense.setUuid(UUID.randomUUID().toString());
        expense.setStatus(ExpenseService.STATUS_VERIFIED_UNBOOKED);
        expense.setJournalnumber(16);
        expense.setAccountingyear("2025/2026");
        expense.setVouchernumber(12345);
        expense.setExpensedate(LocalDate.of(2026, 6, 1));
        expense.setDatecreated(LocalDate.of(2026, 6, 1));
        expense.setDatemodified(LocalDate.of(2026, 6, 1));
        return expense;
    }
}
