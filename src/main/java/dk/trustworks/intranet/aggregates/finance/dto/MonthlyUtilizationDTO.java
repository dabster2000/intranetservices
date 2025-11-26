package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing monthly utilization and capacity metrics for Chart B.
 * Used by CxO Executive Dashboard to display Utilization & Capacity Trend.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyUtilizationDTO {

    /** Month key in format YYYYMM (e.g., "202501") */
    private String monthKey;

    /** Year component (e.g., 2025) */
    private int year;

    /** Month number component (1-12) */
    private int monthNumber;

    /** User-friendly month label (e.g., "Jan 2025") */
    private String monthLabel;

    // Hours breakdown (for stacked columns)

    /** Total billable work hours for the month */
    private double billableHours;

    /** Total non-billable work hours (gross - billable - absence) */
    private double nonBillableHours;

    /** Total absence hours (vacation + sick + maternity + other leave) */
    private double absenceHours;

    // Capacity metrics

    /** Net available working hours (gross - unavailable) */
    private double netAvailableHours;

    /** Gross available hours before deductions */
    private double grossAvailableHours;

    /** Utilization percentage: billable / (gross - absence) * 100.
     *  Measures utilization against actual working hours (excludes vacation/sick leave).
     *  Nullable if working hours is zero. */
    private Double utilizationPercent;
}
