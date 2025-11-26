package dk.trustworks.intranet.aggregates.finance.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object for Revenue YTD vs Budget KPI calculation.
 *
 * Contains fiscal year-to-date metrics for CXO Dashboard:
 * - Actual vs Budget revenue comparison
 * - Year-over-year growth analysis
 * - 12-month revenue sparkline data
 */
public class RevenueYTDDataDTO {
    private final double actualYTD;           // Fiscal YTD actual revenue (DKK)
    private final double budgetYTD;           // Fiscal YTD budget revenue (DKK)
    private final double attainmentPercent;   // (Actual / Budget) × 100
    private final double varianceDKK;         // Actual - Budget (DKK)
    private final double priorYearYTD;        // Prior fiscal year YTD actual (DKK)
    private final double yoyChangePercent;    // ((Current - Prior) / Prior) × 100
    private final double[] sparklineData;     // Last 12 months actual revenue (DKK)

    @JsonCreator
    public RevenueYTDDataDTO(
        @JsonProperty("actualYTD") double actualYTD,
        @JsonProperty("budgetYTD") double budgetYTD,
        @JsonProperty("attainmentPercent") double attainmentPercent,
        @JsonProperty("varianceDKK") double varianceDKK,
        @JsonProperty("priorYearYTD") double priorYearYTD,
        @JsonProperty("yoyChangePercent") double yoyChangePercent,
        @JsonProperty("sparklineData") double[] sparklineData
    ) {
        this.actualYTD = actualYTD;
        this.budgetYTD = budgetYTD;
        this.attainmentPercent = attainmentPercent;
        this.varianceDKK = varianceDKK;
        this.priorYearYTD = priorYearYTD;
        this.yoyChangePercent = yoyChangePercent;
        this.sparklineData = sparklineData;
    }

    // Getters (no setters - immutable DTO)
    public double getActualYTD() { return actualYTD; }
    public double getBudgetYTD() { return budgetYTD; }
    public double getAttainmentPercent() { return attainmentPercent; }
    public double getVarianceDKK() { return varianceDKK; }
    public double getPriorYearYTD() { return priorYearYTD; }
    public double getYoyChangePercent() { return yoyChangePercent; }
    public double[] getSparklineData() { return sparklineData; }
}
