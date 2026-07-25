package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.events.ExpenseValidateEventRecorder;
import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class ExpenseServiceEditRevalidatesTest {

    @Inject ExpenseService svc;
    @Inject ExpenseValidateEventRecorder recorder;
    // The EventBus is a @Singleton bean and cannot be @InjectMock'ed; the real
    // bus delivers to ExpenseValidateEventRecorder, and the real consumer is
    // made a no-op by stubbing the AI/file services with a transient error.
    @InjectMock ExpenseAIValidationService aiSvc;
    @InjectMock ExpenseFileService fileSvc;

    @Test
    void editingResetsToSubmittedAndPublishesEvent() {
        when(fileSvc.getFileById(any())).thenReturn(null);
        when(aiSvc.extractExpenseData(any())).thenReturn("stub receipt text");
        when(aiSvc.validateWithExtractedText(any(), any(), any(), any(), any(), any()))
            .thenReturn(ExpenseAIValidationService.AIResult.error("Validation error: test stub"));

        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("user-1");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setAccountname("Test account");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        // Employee-owned NEEDS_ATTENTION drives the reopen path; seed unified state directly.
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner("EMPLOYEE");
        e.setAttentionKind("RECEIPT");
        e.setAiRuleId("R_RECEIPT_READABLE");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);

        svc.maybeReopenForRevalidation(e.getUuid(), "user-1");

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(e.getUuid()));
        assertEquals("SUBMITTED", after.getState());
        assertNull(after.getAttentionOwner());
        assertNull(after.getAiValidationApproved());
        assertNotNull(recorder.awaitReceipt(e.getUuid(), 10_000),
            "expense.validate was never published for the reopened expense");
    }

    @Test
    void processExpenseItemSkipsRowDeletedAfterBatchSelection() {
        Expense selected = new Expense();
        selected.setUuid(java.util.UUID.randomUUID().toString());
        selected.setUseruuid("user-1");
        selected.setAmount(100.0);
        selected.setAccount("3585");
        selected.setAccountname("Test account");
        selected.setExpensedate(java.time.LocalDate.now().minusDays(5));
        selected.setDatecreated(java.time.LocalDate.now().minusDays(5));
        selected.setStatus("VALIDATED");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(selected::persist);

        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            Expense current = Expense.findById(selected.getUuid());
            current.setStatus("DELETED");
            current.setState("DELETED");
        });

        assertDoesNotThrow(() -> svc.processExpenseItem(selected));

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(selected.getUuid()));
        assertEquals("DELETED", after.getStatus());
        assertEquals("DELETED", after.getState());
    }
}
