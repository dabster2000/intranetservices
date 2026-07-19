package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mixed actual+budget 12-month forecast per practice.
 * Used by CXO Practices dashboard for the combined utilization forecast chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PracticeForecastMonthDTO {

    /** Practice code: PM, BA, CYB, DEV, SA */
    private String practiceId;

    /** Month key in format YYYYMM (e.g., "202501") */
    private String monthKey;

    /** Year component */
    private int year;

    /** Month number (1-12) */
    private int monthNumber;

    /** User-friendly month label (e.g., "Jan 2025") */
    private String monthLabel;

    /** Actual billable utilization %; null for future months with no actuals */
    private Double actualUtilizationPct;

    /** Budget utilization %; null if no budget hours for this month */
    private Double budgetUtilizationPct;

    /** Fixed target utilization % (constant) */
    private double targetUtilizationPct;
}
