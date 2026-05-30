package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the bulk-JPQL seams keep the unified state columns correct (Phase-0 follow-up #1).
 * @QuarkusTest — runs in CI (needs cvtool.username); skipped locally.
 */
@QuarkusTest
class ExpenseStateSeamIT {

    @Inject ExpenseService expenseService;

    private Expense persistCreated() {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("seam-user");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setAccountname("Frokost");
        e.setDescription("seam");
        e.setExpensedate(LocalDate.now());
        e.setDatecreated(LocalDate.now());
        e.setStatus("CREATED");
        e.persist();
        return e;
    }

    @Test @TestTransaction
    void updateStatus_setsTailState() {
        Expense e = persistCreated();
        Expense.getEntityManager().flush();

        expenseService.updateStatus(e, ExpenseService.STATUS_VERIFIED_UNBOOKED);
        Expense.getEntityManager().clear();

        Expense round = Expense.findById(e.getUuid());
        assertEquals(ExpenseStateDeriver.POSTED, round.getState());
        assertNull(round.getAttentionOwner());
    }

    @Test @TestTransaction
    void updateStatus_technicalFailure_setsAccountingTechnical() {
        Expense e = persistCreated();
        Expense.getEntityManager().flush();

        expenseService.updateStatus(e, ExpenseService.STATUS_UP_FAILED, "boom");
        Expense.getEntityManager().clear();

        Expense round = Expense.findById(e.getUuid());
        assertEquals(ExpenseStateDeriver.NEEDS_ATTENTION, round.getState());
        assertEquals(ExpenseStateDeriver.OWNER_ACCOUNTING, round.getAttentionOwner());
        assertEquals(ExpenseStateDeriver.KIND_TECHNICAL, round.getAttentionKind());
    }
}
