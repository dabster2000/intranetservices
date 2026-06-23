package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseSyncResponseHandlingTest {

    @Mock
    EconomicsService economicsService;

    @Mock
    ExpenseService expenseService;

    @Mock
    EconomicsAPI api;

    @InjectMocks
    ExpenseSyncBatchlet batchlet;

    @Test
    void raw_429_response_is_retried_and_never_marked_deleted() {
        Expense expense = expense();
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(16, "voucher.voucherNumber$eq:12345", 1000))
                .thenReturn(
                        Response.status(429).header("Retry-After", "0").entity("throttled").build(),
                        Response.status(429).entity("still throttled").build());

        EconomicsRetryExecutor retry = new EconomicsRetryExecutor(1, millis -> {});

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.THROTTLED, outcome);
        verify(api, times(2)).getJournalEntries(16, "voucher.voucherNumber$eq:12345", 1000);
        verify(expenseService, never()).updateStatus(any(Expense.class), eq(ExpenseService.STATUS_DELETED));
    }

    @Test
    void raw_non_success_response_leaves_expense_status_unchanged() {
        Expense expense = expense();
        when(economicsService.getApiForExpense(expense)).thenReturn(api);
        when(api.getJournalEntries(16, "voucher.voucherNumber$eq:12345", 1000))
                .thenReturn(Response.status(500).entity("boom").build());

        EconomicsRetryExecutor retry = new EconomicsRetryExecutor(0, millis -> {});

        ExpenseSyncBatchlet.SyncOutcome outcome = batchlet.syncExpense(expense, retry);

        assertEquals(ExpenseSyncBatchlet.SyncOutcome.ERROR, outcome);
        verify(api, never()).getYearEntries(any(), any(), eq(1000), eq(0));
        verify(expenseService, never()).updateStatus(any(Expense.class), eq(ExpenseService.STATUS_DELETED));
    }

    private static Expense expense() {
        Expense expense = new Expense();
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
