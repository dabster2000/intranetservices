package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Monthly actual revenue vs budget revenue for trailing 12 months.
 * Used by the Revenue Forecast (Actual vs Budget) chart.
 */
public record RevenueVsBudgetMonthDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        /** Actual net revenue (invoiced). Null if no data for that month. */
        Double actualRevenueDkk,
        /** Budget revenue target. Null if no data for that month. */
        Double budgetRevenueDkk
) {}
