package dk.trustworks.intranet.expenseservice.jobs;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseStuckDetectionBatchletTest {

    @Inject ExpenseStuckDetectionBatchlet job;

    @Test @TestTransaction
    void countsStuckRows() {
        Expense e = new Expense();
        e.setUuid(java.util.UUID.randomUUID().toString());
        e.setUseruuid("u");
        e.setAmount(1.0);
        e.setAccount("1");
        e.setExpensedate(java.time.LocalDate.now());
        e.setDatecreated(java.time.LocalDate.now().minusDays(10));
        e.setDatemodified(java.time.LocalDate.now().minusDays(10));
        e.setStatus("CREATED");
        // Seed the unified state directly — countStuck() queries state/attentionOwner,
        // and the hook no longer derives NEEDS_ATTENTION from the legacy review_state.
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner("EMPLOYEE");
        e.setAttentionKind("RECEIPT");
        e.persist();

        long stuck = job.countStuck();
        assertTrue(stuck >= 1, "expected at least one stuck row");
    }
}
