package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Herfindahl-Hirschman Index (HHI) Concentration Metric
 *
 * The HHI measures market concentration by summing the squares of market shares.
 * Formula: HHI = Σ(market_share_percentage²)
 * Range: 0-10,000
 * Lower values indicate more diversification (better)
 * Higher values indicate more concentration (risky)
 *
 * Example: 5 equal clients (20% each) → HHI = 5×(20²) = 2,000
 *
 * Used by CxO Dashboard Client Portfolio Tab.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConcentrationIndexDTO {

    /**
     * Current TTM HHI value (0-10,000 scale)
     * Lower is better (more diversified)
     */
    private double currentHHI;

    /**
     * Prior TTM HHI value (1 year earlier)
     */
    private double priorHHI;

    /**
     * Change in HHI points (currentHHI - priorHHI)
     * Negative change is good (decreasing concentration)
     * Positive change is bad (increasing concentration)
     */
    private double changePoints;

    /**
     * 12-month sparkline of HHI values
     * Shows monthly trend in concentration
     */
    private double[] sparklineData;
}
