package dk.trustworks.intranet.aggregates.finance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing one month of accumulated revenue data for the fiscal year chart.
 * Used by CxO Executive Dashboard to display Accumulated Revenue (FY).
 * Covers all 12 fiscal months (Jul-Jun), with isActual=true for past months.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyAccumulatedRevenueDTO {

    /** Month key in format YYYYMM (e.g., "202507") */
    private String monthKey;

    /** Calendar year (e.g., 2025) */
    private int year;

    /** Calendar month number (1-12) */
    private int monthNumber;

    /** User-friendly month label (e.g., "Jul 2025") */
    private String monthLabel;

    /**
     * Fiscal month number within the fiscal year (1=Jul, 2=Aug, ..., 6=Dec, 7=Jan, ..., 12=Jun).
     */
    private int fiscalMonthNumber;

    /** Monthly recognized revenue in DKK for this calendar month */
    private double monthlyRevenueDkk;

    /** Running accumulated sum from fiscal month 1 through this month */
    private double accumulatedRevenueDkk;

    /**
     * True if this month has actual/past data from fact_project_financials_mat.
     * False if this is a future month with no actuals yet (revenue will be 0).
     */
    @JsonProperty("isActual")
    private boolean isActual;
}
