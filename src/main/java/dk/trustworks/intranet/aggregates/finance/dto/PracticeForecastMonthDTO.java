package dk.trustworks.intranet.aggregates.finance.dto;

import dk.trustworks.intranet.aggregates.utilization.dto.ActualDataStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

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

    /** Budget utilization %; zero when capacity exists but no budget is allocated, null without capacity. */
    private Double budgetUtilizationPct;

    /** Fixed target utilization % (constant) */
    private double targetUtilizationPct;

    /** Whether this row is a completed actual, provisional current MTD, or forward budget month. */
    private PracticeForecastPeriodType periodType;

    /** Last completed Copenhagen date included in actual fields; null for forward months. */
    private LocalDate actualThroughDate;

    /** Requested and pipeline-certified cutoffs for source-completeness disclosure. */
    private LocalDate requestedActualThroughDate;
    private LocalDate actualDataThroughDate;
    private ActualDataStatus actualDataStatus;
    private Integer actualSourceLagDays;
    private Instant sourceRefreshedAt;

    /** Underlying hours make the percentage and target gap independently auditable. */
    private Double actualBillableHours;
    private Double actualNetAvailableHours;
    private Double budgetHours;
    private Double budgetNetAvailableHours;

    /** Positive shortfall to the 80% target; zero when at/above target; null when basis is absent. */
    private Double actualGapHoursToTarget;
    private Double budgetGapHoursToTarget;

    /** Effective-dated attribution method, coverage boundary, and pre-coverage fallback disclosure. */
    private String practiceAttribution;
    private LocalDate practiceAttributionCoverageStartDate;
    private String attributionNote;
}
