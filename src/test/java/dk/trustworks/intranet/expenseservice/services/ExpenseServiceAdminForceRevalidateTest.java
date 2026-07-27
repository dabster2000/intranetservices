package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.events.ExpenseValidateEventRecorder;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@code adminForceRevalidate} on a previously-APPROVED expense (status
 * VALIDATED / state APPROVED) must reset status to CREATED alongside the
 * SUBMITTED state reset. VALIDATED is a status-derived state, so leaving it
 * in place lets the {@code @PreUpdate} sync hook re-derive APPROVED at commit
 * flush — silently undoing the reset — and the consumer's status guard
 * (status != CREATED) then skips the re-validation entirely, leaving the
 * expense headed for e-conomic upload with its AI verdict wiped.
 *
 * <p>Statuses past the AI-decided head (already in the e-conomic pipeline,
 * technical exceptions, terminal) must be rejected with 409 instead of
 * silently no-op'ing.
 */
@QuarkusTest
class ExpenseServiceAdminForceRevalidateTest {

    @Inject ExpenseService svc;
    @Inject ExpenseValidateEventRecorder recorder;
    @InjectMock ExpenseAIValidationService aiSvc;
    @InjectMock ExpenseFileService fileSvc;

    private Expense newApprovedExpense() {
        Expense e = new Expense();
        e.setUseruuid("user-1");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setAccountname("Test account");
        e.setExpensedate(java.time.LocalDate.now());
        e.setStatus(ExpenseService.STATUS_VALIDATED);
        e.setAiValidationApproved(true);
        e.setAiValidationReason("approved under the old rule catalog");
        e.setAiValidationCount(1);
        e.setAiOutcome("APPROVE");
        e.setAiConfidence(0.95);
        return e;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void adminForceRevalidate_onValidatedExpense_resetsStatusAndRerunsAi() {
        when(fileSvc.getFileById(any())).thenReturn(null);
        when(aiSvc.extractExpenseData(any())).thenReturn("stub receipt text");
        when(aiSvc.validateWithExtractedText(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExpenseAIValidationService.AIResult(true, "ok", List.of(),
                        ExpenseAIValidationService.AIResult.OUTCOME_APPROVE, 0.99, List.of(), null, null));

        Expense e = newApprovedExpense();
        QuarkusTransaction.requiringNew().run(e::persist);
        // @PrePersist derives APPROVED from VALIDATED — the previously-decided head.
        Expense seeded = QuarkusTransaction.requiringNew().call(() -> Expense.findById(e.getUuid()));
        assertEquals(ExpenseStateDeriver.APPROVED, seeded.getState());

        svc.adminForceRevalidate(e.getUuid(), "admin-1");

        // The committed reset must survive the @PreUpdate sync hook: CREATED is not a
        // status-derived status, so the SUBMITTED head reset is preserved.
        ExpenseValidateEventRecorder.Receipt receipt = recorder.awaitReceipt(e.getUuid(), 10_000);
        assertNotNull(receipt, "expense.validate was never delivered after commit");
        assertEquals(ExpenseStateDeriver.SUBMITTED, receipt.state(),
                "the SUBMITTED reset was overwritten at flush — the consumer would skip re-validation");

        // The consumer must ACCEPT the expense (status guard + state guard) and re-judge it.
        Expense revalidated = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Expense current = QuarkusTransaction.requiringNew()
                    .call(() -> Expense.findById(e.getUuid()));
            if (current != null && current.getAiValidationCount() >= 2) {
                revalidated = current;
                break;
            }
            sleep(100);
        }

        assertNotNull(revalidated,
                "AI re-validation never ran — the consumer skipped the force-revalidated expense");
        assertEquals(2, revalidated.getAiValidationCount());
        assertTrue(Boolean.TRUE.equals(revalidated.getAiValidationApproved()));
        assertEquals(ExpenseService.STATUS_VALIDATED, revalidated.getStatus());
        assertEquals(ExpenseStateDeriver.APPROVED, revalidated.getState());
    }

    @Test
    void adminForceRevalidate_onPipelineStatus_rejectsWith409AndPreservesAiFields() {
        Expense e = newApprovedExpense();
        e.setStatus(ExpenseService.STATUS_UPLOADED); // voucher already in e-conomic
        QuarkusTransaction.requiringNew().run(e::persist);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> svc.adminForceRevalidate(e.getUuid(), "admin-1"));
        assertEquals(409, ex.getResponse().getStatus());

        Expense unchanged = QuarkusTransaction.requiringNew().call(() -> Expense.findById(e.getUuid()));
        assertEquals(ExpenseService.STATUS_UPLOADED, unchanged.getStatus());
        assertEquals(ExpenseStateDeriver.POSTING, unchanged.getState());
        assertTrue(Boolean.TRUE.equals(unchanged.getAiValidationApproved()),
                "409 path must not wipe the AI audit fields");
        assertEquals(1, unchanged.getAiValidationCount());

        sleep(400);
        assertNull(recorder.receipt(e.getUuid()),
                "no expense.validate event may be published for a rejected force-revalidate");
    }
}
