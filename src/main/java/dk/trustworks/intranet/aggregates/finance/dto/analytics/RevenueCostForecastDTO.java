package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Revenue vs. cost data point for TTM actuals and forecast months.
 *
 * <p>For actual months (isForecast=false):
 * <ul>
 *   <li>{@code registeredRevenueDkk} from {@code fact_user_day}.</li>
 *   <li>{@code invoiceRevenueDkk} from {@code fact_company_revenue_mat}.</li>
 *   <li>{@code directDeliveryCostDkk} = signed GL DIRECT_COSTS from {@code finance_details}
 *       (cost_type='DIRECT_COSTS' on {@code accounting_accounts}) + synthesized QUEUED
 *       INTERNAL cost attributed to the debtor company. Same cost stack the EBITDA
 *       chart's {@code monthlyDirectCostDkk} uses, so the two views reconcile.</li>
 *   <li>{@code totalCostDkk} = OPEX + Salaries (from {@code DistributionAwareOpexProvider})
 *       + {@code directDeliveryCostDkk}. Equals every cost component the EBITDA chart
 *       subtracts; therefore {@code invoiceRevenueDkk − totalCostDkk ≈ accumulated EBITDA}.</li>
 * </ul>
 *
 * <p>For forecast months (isForecast=true):
 * <ul>
 *   <li>Revenue fields carry forecast budget + weighted pipeline.</li>
 *   <li>{@code directDeliveryCostDkk} = forecast revenue × (1 − TTM gross margin) — same
 *       formula the EBITDA forecast uses, so accumulated trajectories align.</li>
 *   <li>{@code totalCostDkk} = flat TTM monthly OPEX+Salaries average + forecast
 *       {@code directDeliveryCostDkk}.</li>
 * </ul>
 *
 * <p>{@code avgCostDkk} is the flat TTM average across the completed months — same value
 * on every row, used only as a horizontal reference line in charts.
 *
 * @param directDeliveryCostDkk Component of {@code totalCostDkk}; populated on every row
 *                              (zero is valid when no GL data exists for the month).
 *                              The frontend tooltip splits Total Cost into OPEX+Salaries
 *                              ({@code totalCostDkk − directDeliveryCostDkk}) and this field.
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
