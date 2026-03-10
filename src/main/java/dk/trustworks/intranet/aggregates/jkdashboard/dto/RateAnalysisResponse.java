package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Rate analysis for JK billing rates: monthly trends, rate bands, and rate-vs-billing scatter data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateAnalysisResponse {
    private List<MonthlyRateEntry> monthlyRates;
    private List<RateBandEntry> rateBands;
    private List<RateVsBillingEntry> rateVsBilling;
}
