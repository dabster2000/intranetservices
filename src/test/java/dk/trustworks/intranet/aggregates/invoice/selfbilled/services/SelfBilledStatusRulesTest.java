package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLineStatus;
import org.junit.jupiter.api.Test;

import static dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLineStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Voucher status is a projection of (human marks, assignments, group deltas):
 *   IGNORED is sticky (human mark or net-zero voucher) until explicitly unmarked.
 *   SAME_COMPANY without assignments is the sticky human mark; with assignments it
 *   is computed (all assignments resolve issuer == debtor).
 *   SETTLED means every cross-company (consultant, period) group of the voucher's
 *   assignments has |delta| <= 1 kr.
 */
class SelfBilledStatusRulesTest {

    @Test
    void ignored_is_sticky() {
        assertEquals(IGNORED, SelfBilledStatusRules.recompute(IGNORED, true, false, true));
        assertEquals(IGNORED, SelfBilledStatusRules.recompute(IGNORED, false, false, false));
    }

    @Test
    void human_same_company_mark_without_assignments_is_kept() {
        assertEquals(SAME_COMPANY, SelfBilledStatusRules.recompute(SAME_COMPANY, false, false, false));
    }

    @Test
    void no_assignments_means_unassigned() {
        assertEquals(UNASSIGNED, SelfBilledStatusRules.recompute(ASSIGNED, false, false, false));
        assertEquals(UNASSIGNED, SelfBilledStatusRules.recompute(SETTLED, false, false, false));
        assertEquals(UNASSIGNED, SelfBilledStatusRules.recompute(UNASSIGNED, false, false, false));
    }

    @Test
    void all_same_company_assignments_compute_same_company() {
        assertEquals(SAME_COMPANY, SelfBilledStatusRules.recompute(UNASSIGNED, true, true, false));
    }

    @Test
    void same_company_mark_is_overridden_once_assignments_exist() {
        // human SAME_COMPANY mark is overridden once cross-company assignments exist
        assertEquals(ASSIGNED, SelfBilledStatusRules.recompute(SAME_COMPANY, true, false, false));
        assertEquals(SAME_COMPANY, SelfBilledStatusRules.recompute(SAME_COMPANY, true, true, false));
    }

    @Test
    void cross_company_assignments_are_assigned_until_groups_settle() {
        assertEquals(ASSIGNED, SelfBilledStatusRules.recompute(UNASSIGNED, true, false, false));
        assertEquals(SETTLED, SelfBilledStatusRules.recompute(ASSIGNED, true, false, true));
        // editing after settle: group delta re-opens -> back to ASSIGNED (spec §4.1)
        assertEquals(ASSIGNED, SelfBilledStatusRules.recompute(SETTLED, true, false, false));
    }
}
