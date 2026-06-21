package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI-gated (@QuarkusTest, needs DB): proves selectExpensesToSync includes
 * VERIFIED_UNBOOKED and recently-modified rows, and excludes the stable
 * booked/deleted tail older than the recency window.
 */
@QuarkusTest
class ExpenseSyncSelectionIT {

    private Expense seed(String status, LocalDate datemodified) {
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid(UUID.randomUUID().toString());
        e.setStatus(status);
        e.setJournalnumber(16);
        e.setAccountingyear("2025/2026");
        e.setVouchernumber(12345);
        e.setExpensedate(LocalDate.of(2025, 1, 1));
        e.setDatecreated(LocalDate.of(2025, 1, 1));
        e.setDatemodified(datemodified);
        e.persist();
        return e;
    }

    @Test
    @TestTransaction
    void selects_unbooked_and_recent_excludes_old_terminal() {
        LocalDate today = LocalDate.of(2026, 6, 21);
        LocalDate cutoff = ExpenseSyncBatchlet.computeCutoff(today, 30); // 2026-05-22

        Expense unbookedOld = seed(ExpenseService.STATUS_VERIFIED_UNBOOKED, LocalDate.of(2025, 1, 1));
        Expense bookedRecent = seed(ExpenseService.STATUS_VERIFIED_BOOKED, LocalDate.of(2026, 6, 10));
        Expense bookedOld = seed(ExpenseService.STATUS_VERIFIED_BOOKED, LocalDate.of(2025, 1, 1));
        Expense deletedOld = seed(ExpenseService.STATUS_DELETED, LocalDate.of(2025, 1, 1));

        List<String> selected = ExpenseSyncBatchlet.selectExpensesToSync(cutoff)
                .stream().map(Expense::getUuid).collect(Collectors.toList());

        assertTrue(selected.contains(unbookedOld.getUuid()), "VERIFIED_UNBOOKED always re-synced");
        assertTrue(selected.contains(bookedRecent.getUuid()), "recently modified row re-synced");
        assertFalse(selected.contains(bookedOld.getUuid()), "old booked row dropped from scan");
        assertFalse(selected.contains(deletedOld.getUuid()), "old deleted row dropped from scan");
    }
}
