package dk.trustworks.intranet.expenseservice.batch;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.utils.UndecodableReceiptException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class ExpenseItemWriterTest {

    @Mock
    ExpenseService expenseService;

    ExpenseItemWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        writer = new ExpenseItemWriter();
        writer.expenseService = expenseService;
        writer.throttleMsStr = "0";
        writer.open(null);
    }

    @Test
    void undecodableReceiptIsParkedAndNextExpenseContinues() throws Exception {
        Expense undecodable = expense("undecodable-expense");
        Expense valid = expense("valid-expense");
        UndecodableReceiptException failure = new UndecodableReceiptException();

        doThrow(failure).when(expenseService).processExpenseItem(undecodable);

        assertDoesNotThrow(() -> writer.writeItems(List.<Object>of(undecodable, valid)));

        InOrder inOrder = inOrder(expenseService);
        inOrder.verify(expenseService).processExpenseItem(undecodable);
        inOrder.verify(expenseService).updateStatus(
                undecodable,
                ExpenseService.STATUS_UP_FAILED,
                UndecodableReceiptException.DEFAULT_MESSAGE);
        inOrder.verify(expenseService).processExpenseItem(valid);
    }

    /**
     * An undecodable receipt is a FAILURE the job must surface, not a skip: before 2026-07-30
     * a prod run that parked 11 of 20 expenses this way still logged as a successful completion.
     */
    @Test
    void undecodableReceiptCountsAsFailedNotSkipped() throws Exception {
        Expense undecodable = expense("undecodable-expense");
        Expense valid = expense("valid-expense");

        doThrow(new UndecodableReceiptException(UndecodableReceiptException.HEIC_MESSAGE))
                .when(expenseService).processExpenseItem(undecodable);

        writer.writeItems(List.<Object>of(undecodable, valid));

        assertEquals(2, writer.processedCount);
        assertEquals(1, writer.successCount);
        assertEquals(1, writer.failedCount);
        assertEquals(0, writer.skippedCount);
    }

    private Expense expense(String uuid) {
        Expense expense = new Expense();
        expense.setUuid(uuid);
        return expense;
    }
}
