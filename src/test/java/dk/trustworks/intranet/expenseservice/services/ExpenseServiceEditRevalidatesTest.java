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
    void editingClearsReviewStateAndPublishesEvent() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("user-1");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now());
        e.setStatus("CREATED");
        e.setReviewState("NEEDS_FIX");
        e.setAiRuleId("R_RECEIPT_READABLE");
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);

        svc.maybeReopenForRevalidation(e.getUuid(), "user-1");

        Expense after = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Expense.findById(e.getUuid()));
        assertNull(after.getReviewState());
        assertNull(after.getAiValidationApproved());
        verify(bus).publish(eq("expense.validate"), eq(e.getUuid()));
    }
}
