package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Monthly absence breakdown showing how gross capacity is reduced to net available and billable.
 * Used by CXO Delivery dashboard for the absence impact waterfall chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyAbsenceWaterfallDTO {

    /** Month key in format YYYYMM (e.g., "202501") */
    private String monthKey;

    /** Year component */
    private int year;

    /** Month number (1-12) */
    private int monthNumber;

    /** User-friendly month label (e.g., "Jan 2025") */
    private String monthLabel;

    /** Gross available hours before any deductions */
    private double grossAvailableHours;

    /** Hours lost to vacation */
    private double vacationHours;

    /** Hours lost to sickness */
    private double sickHours;

    /** Hours lost to maternity leave */
    private double maternityLeaveHours;

    /** Hours lost to non-paid leave */
    private double nonPaidLeaveHours;

    /** Hours lost to paid leave */
    private double paidLeaveHours;

    /** Hours marked as unavailable */
    private double unavailableHours;

    /** Net available hours after all absence deductions */
    private double netAvailableHours;

    /** Billable hours */
    private double billableHours;

    /** Non-billable hours (net_available - billable, clamped to 0) */
    private double nonBillableHours;

    /** Net utilization percentage: billable / net_available * 100. Null if net_available is 0. */
    private Double utilizationPct;
}
