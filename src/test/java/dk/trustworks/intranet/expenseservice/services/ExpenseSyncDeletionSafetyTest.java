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

    /** Voucher absent from the stored journal, the accounting year, and every swept journal. */
    private void stubNotFoundAnywhere(Expense expense) {
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(eq(16), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
        when(api.getYearEntries(eq("2025_6_2026"), eq("voucherNumber$eq:12345"), eq(1000), eq(0)))
                .thenAnswer(inv -> ok(EMPTY));
        when(api.getJournals(ExpenseSyncBatchlet.JOURNALS_PAGESIZE)).thenAnswer(inv -> ok(JOURNALS_LISTING));
        when(api.getJournalEntries(eq(42), eq(JOURNAL_FILTER), eq(1000))).thenAnswer(inv -> ok(EMPTY));
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
