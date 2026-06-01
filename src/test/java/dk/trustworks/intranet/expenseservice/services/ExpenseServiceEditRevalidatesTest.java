package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
class ExpenseServiceEditRevalidatesTest {

    @Inject ExpenseService svc;
    @InjectMock EventBus bus;

    @Test
    void editingResetsToSubmittedAndPublishesEvent() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("user-1");
        e.setAmount(100.0);
        e.setAccount("3585");
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
        verify(bus).publish(eq("expense.validate"), eq(e.getUuid()));
    }

    @Test
    void processExpenseItemSkipsRowDeletedAfterBatchSelection() {
        Expense selected = new Expense();
        selected.setUuid(java.util.UUID.randomUUID().toString());
        selected.setUseruuid("user-1");
        selected.setAmount(100.0);
        selected.setAccount("3585");
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
