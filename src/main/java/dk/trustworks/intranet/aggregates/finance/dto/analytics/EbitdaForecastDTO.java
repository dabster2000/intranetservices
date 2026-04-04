package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * EBITDA forecast data point for one fiscal-year month.
 *
 * <p>For actual months (isActual=true):
 * revenue from fact_company_revenue_mat,
 * delivery cost from fact_project_financials_mat,
 * OPEX from fact_opex_mat (cost_type='OPEX' only).
 *
 * <p>For forecast months (isActual=false):
 * revenue = budget + weighted pipeline (excl. WON),
 * delivery cost = forecast revenue * TTM delivery cost ratio,
 * OPEX = TTM average monthly OPEX (flat projection).
 *
 * <p>EBITDA = revenue - directDeliveryCost - opex.
 * accumulatedEbitdaDkk is the running sum across the fiscal year.
 */
public record EbitdaForecastDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        int fiscalMonthNumber,
        double revenueDkk,
        double directDeliveryCostDkk,
        double opexDkk,
        double ebitdaDkk,
        double accumulatedEbitdaDkk,
        boolean isActual
) {}
