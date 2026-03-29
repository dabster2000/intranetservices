package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Forward-looking staffing allocation for the next 6 months.
 * Each row represents a team member's allocation across future months.
 */
public record TeamForwardAllocationDTO(
        List<String> months,
        List<MemberAllocation> members
) {

    public record MemberAllocation(
            String userId,
            String firstname,
            String lastname,
            /** Budget hours per month, one entry per month in the months list */
            List<Double> budgetHoursByMonth,
            /** Net available hours per month */
            List<Double> availableHoursByMonth,
            /** Allocation percentage per month: budget / available * 100 */
            List<Double> allocationPercentByMonth
    ) {}
}
