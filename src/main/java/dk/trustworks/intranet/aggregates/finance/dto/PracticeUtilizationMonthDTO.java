package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Monthly utilization data for one practice in one month.
 * Used by CXO Practices dashboard for utilization history chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PracticeUtilizationMonthDTO {

    /** Practice code: PM, BA, CYB, DEV, SA */
    private String practiceId;

    /** Month key in format YYYYMM (e.g., "202501") */
    private String monthKey;

    /** Year component (e.g., 2025) */
    private int year;

    /** Month number component (1-12) */
    private int monthNumber;

    /** User-friendly month label (e.g., "Jan 2025") */
    private String monthLabel;

    /** Net available hours for the practice in this month */
    private double netAvailableHours;

    /** Billable hours for the practice in this month */
    private double billableHours;

    /** Utilization percentage: billable / net_available * 100. Null if no available hours. */
    private Double utilizationPct;
}
