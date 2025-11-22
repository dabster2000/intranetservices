package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing monthly revenue and margin metrics for Chart A.
 * Used by CxO Executive Dashboard to display Revenue & Margin Trend.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyRevenueMarginDTO {

    /** Month key in format YYYYMM (e.g., "202501") */
    private String monthKey;

    /** Year component (e.g., 2025) */
    private int year;

    /** Month number component (1-12) */
    private int monthNumber;

    /** User-friendly month label (e.g., "Jan 2025") */
    private String monthLabel;

    /** Total recognized revenue in DKK for the month */
    private double revenueDkk;

    /** Total direct delivery cost in DKK for the month */
    private double costDkk;

    /** Gross margin percentage: (revenue - cost) / revenue * 100.
     *  Nullable if revenue is zero for the month. */
    private Double marginPercent;
}
