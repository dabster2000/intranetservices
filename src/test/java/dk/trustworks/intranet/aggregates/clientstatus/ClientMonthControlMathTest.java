package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCellState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCellState.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the client×month controlling decision helpers: drift detection, gap counting,
 * and bulk-approve eligibility. No Quarkus, no DB.
 */
class ClientMonthControlMathTest {

    // --- isDrifted ---

    @Test
    void isDrifted_falseWhenNotApproved() {
        // Even a huge divergence is irrelevant when there is no approval snapshot.
        assertFalse(ClientStatusMath.isDrifted(false, 0d, 0d, 1_000_000d, 0d));
    }

    @Test
    void isDrifted_falseWithinToleranceOnBothAxes() {
        assertFalse(ClientStatusMath.isDrifted(true, 100_000d, 80_000d, 100_500d, 79_500d));
    }

    @Test
    void isDrifted_falseExactlyAtTolerance() {
        // > tolerance drifts; exactly 1000 on either axis does NOT (boundary).
        assertFalse(ClientStatusMath.isDrifted(true, 100_000d, 80_000d, 101_000d, 81_000d));
    }

    @Test
    void isDrifted_trueJustBeyondToleranceOnExpected() {
        assertTrue(ClientStatusMath.isDrifted(true, 100_000d, 80_000d, 101_000.01d, 80_000d));
    }

    @Test
    void isDrifted_trueJustBeyondToleranceOnInvoiced() {
        assertTrue(ClientStatusMath.isDrifted(true, 100_000d, 80_000d, 100_000d, 78_999.99d));
    }

    @Test
    void isDrifted_nullSnapshotTreatedAsZero() {
        // Approved with null snapshot (shouldn't happen, but robust): current > tolerance ⇒ drift.
        assertTrue(ClientStatusMath.isDrifted(true, null, null, 2_000d, 0d));
        assertFalse(ClientStatusMath.isDrifted(true, null, null, 500d, -500d));
    }

    @Test
    void isEffectivelyApproved_onlyWhenApprovedAndNotDrifted() {
        assertTrue(ClientStatusMath.isEffectivelyApproved(true, false));
        assertFalse(ClientStatusMath.isEffectivelyApproved(true, true));
        assertFalse(ClientStatusMath.isEffectivelyApproved(false, false));
    }

    // --- isProvisional ---

    @Test
    void isProvisional_currentMonthBeforeCutoff() {
        assertTrue(ClientStatusMath.isProvisional("202606", LocalDate.of(2026, 6, 24)));
        assertTrue(ClientStatusMath.isProvisional("202606", LocalDate.of(2026, 7, 5)));
        assertFalse(ClientStatusMath.isProvisional("202606", LocalDate.of(2026, 7, 10)));
        assertFalse(ClientStatusMath.isProvisional("202603", LocalDate.of(2026, 6, 24)));
    }

    // --- countsAsGap ---

    @Test
    void countsAsGap_provisionalNeverCounts() {
        assertFalse(ClientStatusMath.countsAsGap(NOT_INVOICED, true, false, false));
    }

    @Test
    void countsAsGap_notInvoicedAndPartialCountWhenUnapproved() {
        assertTrue(ClientStatusMath.countsAsGap(NOT_INVOICED, false, false, false));
        assertTrue(ClientStatusMath.countsAsGap(PARTIAL, false, false, false));
    }

    @Test
    void countsAsGap_fullOverAndNoActivityNeverCount() {
        assertFalse(ClientStatusMath.countsAsGap(FULL, false, false, false));
        assertFalse(ClientStatusMath.countsAsGap(OVER, false, false, false));
        assertFalse(ClientStatusMath.countsAsGap(NO_ACTIVITY, false, false, false));
    }

    @Test
    void countsAsGap_effectivelyApprovedExcluded_butDriftedReCounts() {
        assertFalse(ClientStatusMath.countsAsGap(NOT_INVOICED, false, true, false), "approved && !drift ⇒ no gap");
        assertTrue(ClientStatusMath.countsAsGap(NOT_INVOICED, false, true, true), "drifted approval ⇒ gap again");
    }

    // --- isBulkApprovable ---

    @Test
    void bulkApprovable_provisionalExcludedInBothScopes() {
        assertFalse(ClientStatusMath.isBulkApprovable(FULL, true, false, false, true));
        assertFalse(ClientStatusMath.isBulkApprovable(FULL, true, false, false, false));
    }

    @Test
    void bulkApprovable_noActivityExcluded() {
        assertFalse(ClientStatusMath.isBulkApprovable(NO_ACTIVITY, false, false, false, false));
    }

    @Test
    void bulkApprovable_fullOnly_onlyFullCells() {
        assertTrue(ClientStatusMath.isBulkApprovable(FULL, false, false, false, true));
        assertFalse(ClientStatusMath.isBulkApprovable(PARTIAL, false, false, false, true));
        assertFalse(ClientStatusMath.isBulkApprovable(NOT_INVOICED, false, false, false, true));
        assertFalse(ClientStatusMath.isBulkApprovable(OVER, false, false, false, true));
    }

    @Test
    void bulkApprovable_allRemaining_anyActiveNonApprovedState() {
        assertTrue(ClientStatusMath.isBulkApprovable(FULL, false, false, false, false));
        assertTrue(ClientStatusMath.isBulkApprovable(PARTIAL, false, false, false, false));
        assertTrue(ClientStatusMath.isBulkApprovable(NOT_INVOICED, false, false, false, false));
        assertTrue(ClientStatusMath.isBulkApprovable(OVER, false, false, false, false));
    }

    @Test
    void bulkApprovable_alreadyEffectivelyApprovedExcluded_butDriftedReEligible() {
        assertFalse(ClientStatusMath.isBulkApprovable(FULL, false, true, false, true), "approved && !drift excluded");
        assertFalse(ClientStatusMath.isBulkApprovable(FULL, false, true, false, false), "approved && !drift excluded");
        assertTrue(ClientStatusMath.isBulkApprovable(FULL, false, true, true, true), "drifted ⇒ re-eligible");
        assertTrue(ClientStatusMath.isBulkApprovable(PARTIAL, false, true, true, false), "drifted ⇒ re-eligible");
    }
}
