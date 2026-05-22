package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Revenue vs. cost data point for TTM actuals and forecast months.
 *
 * <p>For actual months (isForecast=false):
 * registeredRevenueDkk comes from fact_user_day,
 * invoiceRevenueDkk from fact_company_revenue_mat,
 * directDeliveryCostDkk from finance_details (GL cost_type='DIRECT_COSTS'),
 * totalCostDkk is the full economic cost (OPEX + SALARIES from
 * DistributionAwareOpexProvider + directDeliveryCostDkk). This is the same
 * cost stack subtracted from revenue by the EBITDA chart, so
 * (invoiceRevenueDkk - totalCostDkk) reconciles with EBITDA.
 *
 * <p>For forecast months (isForecast=true):
 * both revenue fields carry budget + weighted pipeline.
 * directDeliveryCostDkk = forecastRevenue * (1 - ttmGrossMarginPct/100),
 * mirroring the EBITDA chart's forecast logic.
 * totalCostDkk = flat-TTM-avg OPEX+SALARIES + forecast directDeliveryCostDkk.
 *
 * <p>avgCostDkk is the flat TTM average of totalCostDkk across all 12 completed
 * months (same value for every row — used as a reference line in charts).
 */
public record RevenueCostForecastDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double registeredRevenueDkk,
        double invoiceRevenueDkk,
        double totalCostDkk,
        double directDeliveryCostDkk,
        Double avgCostDkk,
        boolean isForecast
) {}
