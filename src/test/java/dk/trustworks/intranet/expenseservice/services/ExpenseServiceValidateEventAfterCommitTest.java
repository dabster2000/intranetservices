package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.events.ExpenseValidateEventRecorder;
import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The {@code expense.validate} event must be published only after the
 * surrounding transaction commits. {@code ExpenseCreatedConsumer} looks the
 * expense up in its own transaction, so a mid-transaction publish lets it read
 * the database before the insert (or state reset) is visible — it then logs
 * "Expense not found" (or skips on the stale state) and AI validation is
 * silently deferred to the 50-minute sweep.
 *
 * <p>{@link ExpenseValidateEventRecorder} is a second consumer on the same
 * address; it snapshots, at the moment of delivery, what a fresh transaction
 * can see — the same view the real consumer gets. The EventBus itself cannot
 * be {@code @InjectMock}ed (it is a {@code @Singleton} bean), so the real bus
 * is used and the AI/file services are mocked instead.
 */
@QuarkusTest
class ExpenseServiceValidateEventAfterCommitTest {

    @Inject ExpenseService svc;
    @Inject ExpenseValidateEventRecorder recorder;
    @InjectMock ExpenseAIValidationService aiSvc;
    @InjectMock ExpenseFileService fileSvc;

    private Expense newExpense() {
        Expense e = new Expense();
        e.setUseruuid("user-1");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setAccountname("Test account");
        e.setExpensedate(java.time.LocalDate.now());
        return e;
    }

    /**
     * Make the real consumer a no-op: a transient "Validation error:" result
     * leaves every AI field and the state untouched (retried by the sweep), so
     * the recorder's snapshot cannot race a state change from the consumer.
     */
    private void stubConsumerNoOp() {
        when(fileSvc.getFileById(any())).thenReturn(null);
        when(aiSvc.extractExpenseData(any())).thenReturn("stub receipt text");
        when(aiSvc.validateWithExtractedText(any(), any(), any(), any(), any(), any()))
                .thenReturn(ExpenseAIValidationService.AIResult.error("Validation error: test stub"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void processExpense_defersEventUntilCommit_soConsumerSeesTheRow() {
        stubConsumerNoOp();
        when(fileSvc.saveFile(any())).thenReturn(PutObjectResponse.builder().build());
        Expense e = newExpense();
        e.setExpensefile("cmVjZWlwdA==");

        QuarkusTransaction.requiringNew().run(() -> {
            try {
                svc.processExpense(e);
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
            sleep(400);
            assertNull(recorder.receipt(e.getUuid()),
                    "expense.validate was delivered while the insert was still uncommitted");
        });

        ExpenseValidateEventRecorder.Receipt receipt = recorder.awaitReceipt(e.getUuid(), 10_000);
        assertNotNull(receipt, "expense.validate was never delivered after commit");
        assertTrue(receipt.rowVisible(),
                "consumer could not see the committed row — it would log 'Expense not found' and skip AI validation");
    }

    @Test
    void processExpense_consumerRunsAiValidationToCompletion() throws Exception {
        when(fileSvc.saveFile(any())).thenReturn(PutObjectResponse.builder().build());
        when(fileSvc.getFileById(any())).thenReturn(null);
        when(aiSvc.extractExpenseData(any())).thenReturn("stub receipt text");
        when(aiSvc.validateWithExtractedText(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExpenseAIValidationService.AIResult(true, "ok", List.of(),
                        ExpenseAIValidationService.AIResult.OUTCOME_APPROVE, 0.99, List.of(), null, null));
        Expense e = newExpense();
        e.setExpensefile("cmVjZWlwdA==");

        svc.processExpense(e);

        Expense validated = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Expense current = QuarkusTransaction.requiringNew()
                    .call(() -> Expense.findById(e.getUuid()));
            if (current != null && Boolean.TRUE.equals(current.getAiValidationApproved())) {
                validated = current;
                break;
            }
            sleep(100);
        }

        assertNotNull(validated, "AI validation never ran for the freshly created expense");
        assertEquals(ExpenseService.STATUS_VALIDATED, validated.getStatus());
        assertEquals("APPROVED", validated.getState());
        assertEquals(1, validated.getAiValidationCount());
    }

    @Test
    void maybeReopenForRevalidation_defersEventUntilStateResetIsCommitted() {
        stubConsumerNoOp();
        Expense e = newExpense();
        e.setStatus("CREATED");
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner("EMPLOYEE");
        e.setAttentionKind("RECEIPT");
        QuarkusTransaction.requiringNew().run(e::persist);

        QuarkusTransaction.requiringNew().run(() -> {
            svc.maybeReopenForRevalidation(e.getUuid(), "user-1");
            sleep(400);
            assertNull(recorder.receipt(e.getUuid()),
                    "expense.validate was delivered while the state reset was still uncommitted");
        });

        ExpenseValidateEventRecorder.Receipt receipt = recorder.awaitReceipt(e.getUuid(), 10_000);
        assertNotNull(receipt, "expense.validate was never delivered after commit");
        assertEquals("SUBMITTED", receipt.state(),
                "consumer saw the stale pre-reset state — it would skip validation as 'not SUBMITTED'");
    }

    @Test
    void adminForceRevalidate_defersEventUntilStateResetIsCommitted() {
        stubConsumerNoOp();
        Expense e = newExpense();
        e.setStatus("CREATED");
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner("ACCOUNTING");
        e.setAttentionKind("POLICY");
        QuarkusTransaction.requiringNew().run(e::persist);

        QuarkusTransaction.requiringNew().run(() -> {
            svc.adminForceRevalidate(e.getUuid(), "admin-1");
            sleep(400);
            assertNull(recorder.receipt(e.getUuid()),
                    "expense.validate was delivered while the state reset was still uncommitted");
        });

        ExpenseValidateEventRecorder.Receipt receipt = recorder.awaitReceipt(e.getUuid(), 10_000);
        assertNotNull(receipt, "expense.validate was never delivered after commit");
        assertEquals("SUBMITTED", receipt.state(),
                "consumer saw the stale pre-reset state — it would skip validation as 'not SUBMITTED'");
    }
}
