package dk.trustworks.intranet.expenseservice.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpenseDeletePolicyTest {

    private static Expense expense(String status) {
        Expense e = new Expense();
        e.setStatus(status);
        return e;
    }

    @Test
    void allowsOnlyPreEconomicStatusesWithoutVoucherReferences() {
        for (String status : new String[] {"CREATED", "VALIDATED", "UP_FAILED", "NO_FILE", "NO_USER"}) {
            Expense e = expense(status);
            assertTrue(ExpenseDeletePolicy.canDelete(e), status);
            assertNull(ExpenseDeletePolicy.blockedReason(e), status);
        }

        for (String status : new String[] {"PROCESSING", "PROCESSED", "UPLOADED", "VOUCHER_CREATED", "VERIFIED_UNBOOKED", "VERIFIED_BOOKED", "DELETED"}) {
            assertFalse(ExpenseDeletePolicy.canDelete(expense(status)), status);
        }
    }

    @Test
    void blocksVoucherReferencedRowsEvenWhenStatusIsPreEconomic() {
        Expense e = expense("CREATED");
        e.setVouchernumber(42);

        assertFalse(ExpenseDeletePolicy.canDelete(e));
        assertTrue(ExpenseDeletePolicy.blockedReason(e).contains("voucher"));
    }
}
