package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests (no Quarkus, no DB) for the W5 reject-safety guard. */
class ExpenseSpotCheckServiceTest {

    @Test
    void pre_upload_rows_without_voucher_can_hard_reject() {
        assertTrue(ExpenseSpotCheckService.canHardReject("CREATED", 0));
        assertTrue(ExpenseSpotCheckService.canHardReject("VALIDATED", 0));
    }

    @Test
    void any_voucher_blocks_a_hard_reject() {
        assertFalse(ExpenseSpotCheckService.canHardReject("VALIDATED", 4711));
        assertFalse(ExpenseSpotCheckService.canHardReject("VERIFIED_BOOKED", 4711));
    }

    @Test
    void pipeline_and_terminal_statuses_block_a_hard_reject() {
        assertFalse(ExpenseSpotCheckService.canHardReject("PROCESSING", 0));
        assertFalse(ExpenseSpotCheckService.canHardReject("UPLOADED", 0));
        assertFalse(ExpenseSpotCheckService.canHardReject("VERIFIED_UNBOOKED", 0));
        assertFalse(ExpenseSpotCheckService.canHardReject("VERIFIED_BOOKED", 0));
        assertFalse(ExpenseSpotCheckService.canHardReject("CLOSED_MANUAL", 0));
        assertFalse(ExpenseSpotCheckService.canHardReject("DELETED", 0));
    }
}
