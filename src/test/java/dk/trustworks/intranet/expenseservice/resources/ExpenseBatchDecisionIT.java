package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseBatchDecisionDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseBatchDecisionResultDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** @QuarkusTest — runs in CI (needs cvtool.username). */
@QuarkusTest
class ExpenseBatchDecisionIT {

    @Inject ExpenseBatchDecisionResource resource; // @RolesAllowed bypassed for direct call; or use RestAssured

    private String seed(String state, String owner) {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("batch-user");
        e.setAmount(100.0);
        e.setAccount("3585");
        e.setAccountname("Frokost");
        e.setDescription("batch");
        e.setExpensedate(LocalDate.now());
        e.setDatecreated(LocalDate.now());
        e.setStatus("CREATED");
        e.setState(state);
        e.setAttentionOwner(owner);
        e.setAttentionKind(ExpenseStateDeriver.KIND_POLICY);
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(e::persist);
        return e.getUuid();
    }

    @Test
    void batchApprove_appliesToNeedsAttention_skipsOthers() {
        String a = seed(ExpenseStateDeriver.NEEDS_ATTENTION, ExpenseStateDeriver.OWNER_ACCOUNTING);
        String b = seed(ExpenseStateDeriver.APPROVED, null); // already approved → skipped

        ExpenseBatchDecisionResultDTO r = resource.batch(
                new ExpenseBatchDecisionDTO(List.of(a, b), "APPROVE", "looks fine"));

        assertEquals(1, r.updated());
        assertEquals(1, r.skipped().size());
        assertEquals(b, r.skipped().get(0).uuid());

        Expense ra = Expense.findById(a);
        assertEquals(ExpenseStateDeriver.APPROVED, ra.getState());
        assertEquals("VALIDATED", ra.getStatus());
    }

    @Test
    void batchReject_setsRejectedTerminal() {
        String a = seed(ExpenseStateDeriver.NEEDS_ATTENTION, ExpenseStateDeriver.OWNER_ACCOUNTING);
        ExpenseBatchDecisionResultDTO r = resource.batch(
                new ExpenseBatchDecisionDTO(List.of(a), "REJECT", "out of policy"));
        assertEquals(1, r.updated());
        Expense ra = Expense.findById(a);
        assertEquals(ExpenseStateDeriver.REJECTED, ra.getState());
        assertEquals("DELETED", ra.getStatus());
    }
}
