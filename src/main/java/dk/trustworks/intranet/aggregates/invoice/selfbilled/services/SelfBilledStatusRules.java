package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLineStatus;

/**
 * Pure voucher-status projection (no DB, no CDI). IGNORED and the human
 * SAME_COMPANY mark (no assignments) are sticky; everything else is computed
 * from the voucher's assignments and their group deltas. See spec §4.1 lifecycle.
 */
public final class SelfBilledStatusRules {

    private SelfBilledStatusRules() {}

    public static SelfBilledLineStatus recompute(SelfBilledLineStatus current,
                                                 boolean hasAssignments,
                                                 boolean allSameCompany,
                                                 boolean allCrossGroupsSettled) {
        if (current == SelfBilledLineStatus.IGNORED) return current;
        if (!hasAssignments) {
            return current == SelfBilledLineStatus.SAME_COMPANY
                    ? SelfBilledLineStatus.SAME_COMPANY      // sticky human mark
                    : SelfBilledLineStatus.UNASSIGNED;
        }
        if (allSameCompany) return SelfBilledLineStatus.SAME_COMPANY;
        return allCrossGroupsSettled ? SelfBilledLineStatus.SETTLED : SelfBilledLineStatus.ASSIGNED;
    }
}
