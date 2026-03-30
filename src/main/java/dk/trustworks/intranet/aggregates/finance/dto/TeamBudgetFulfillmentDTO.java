package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Budget vs actual hours for a single consultant, FY-to-date through the previous completed month.
 */
public record TeamBudgetFulfillmentDTO(
        String userId,
        String firstname,
        String lastname,
        /** Total budgeted hours for the FY-to-date period (through previous month) */
        double budgetHours,
        /** Actual billable hours registered for the FY-to-date period (through previous month) */
        double actualHours,
        /** Gap = budget - actual (positive = underperformance) */
        double gapHours,
        /** Fulfillment percentage: actual / budget * 100; null if budget is zero */
        Double fulfillmentPercent
) {}
