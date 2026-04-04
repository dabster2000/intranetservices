package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Revenue vs. cost data point for TTM actuals and forecast months.
 *
 * <p>For actual months (isForecast=false):
 * registeredRevenueDkk comes from fact_user_day,
 * invoiceRevenueDkk from fact_company_revenue_mat,
 * totalCostDkk from fact_opex_mat (OPEX + SALARIES).
 *
 * <p>For forecast months (isForecast=true):
 * both revenue fields carry budget + weighted pipeline,
 * totalCostDkk is the TTM flat average monthly cost.
 *
 * <p>avgCostDkk is the flat TTM average across all 12 completed months
 * (same value for every row — used as a reference line in charts).
 */
public record RevenueCostForecastDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double registeredRevenueDkk,
        double invoiceRevenueDkk,
        double totalCostDkk,
        Double avgCostDkk,
        boolean isForecast
) {}
