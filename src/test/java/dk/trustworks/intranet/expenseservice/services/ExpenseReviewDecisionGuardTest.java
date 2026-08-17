package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for the P0 decision guards: TECHNICAL rows must be refused BEFORE any
 * audit write — approving them was a silent no-op (the entity hook re-derives
 * NEEDS_ATTENTION from the technical status in the same transaction), which produced
 * fictional "Accounting approved" audit rows in prod.
 */
class ExpenseReviewDecisionGuardTest {

    private Expense row(String kind, int voucher) {
        Expense e = new Expense();
        e.setStatus("UP_FAILED");
        e.setState("NEEDS_ATTENTION");
        e.setAttentionOwner("ACCOUNTING");
        e.setAttentionKind(kind);
        e.setVouchernumber(voucher);
        return e;
    }

    @Test
    void approve_refusesTechnicalRows() {
        assertThrows(BadRequestException.class,
                () -> ExpenseReviewDecisionService.requireApprovable(row("TECHNICAL", 0)));
    }

    @Test
    void approve_allowsPolicyAndJustificationRows() {
        assertDoesNotThrow(() -> ExpenseReviewDecisionService.requireApprovable(row("POLICY", 0)));
        assertDoesNotThrow(() -> ExpenseReviewDecisionService.requireApprovable(row("JUSTIFICATION", 0)));
        assertDoesNotThrow(() -> ExpenseReviewDecisionService.requireApprovable(row(null, 0)));
    }

    @Test
    void reject_refusesTechnicalRowsWithAVoucher() {
        // Soft-deleting our row would leave the draft voucher alive in e-conomic.
        assertThrows(BadRequestException.class,
                () -> ExpenseReviewDecisionService.requireRejectable(row("TECHNICAL", 4711)));
    }

    @Test
    void reject_allowsVoucherlessTechnicalAndAllPolicyRows() {
        assertDoesNotThrow(() -> ExpenseReviewDecisionService.requireRejectable(row("TECHNICAL", 0)));
        assertDoesNotThrow(() -> ExpenseReviewDecisionService.requireRejectable(row("POLICY", 4711)));
    }
}
