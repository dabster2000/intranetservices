package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.Map;

/**
 * One month of consultant-level invoiced revenue broken down by practice (service line).
 *
 * <p>The {@link #practiceRevenue} map serializes as a JSON object whose keys are
 * the practice ids present in the parent {@link RevenuePracticeDTO#practices()} list,
 * each value being the DKK revenue for that practice in this month. Missing
 * practices are explicitly populated with {@code 0.0} so the frontend can rely
 * on key parity across all months.</p>
 *
 * <p>{@link #marginPercent} is {@code null} for months with no positive revenue;
 * otherwise it is rounded to two decimal places.</p>
 */
public record MonthlyRevenuePracticeDataPoint(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        Map<String, Double> practiceRevenue,
        double totalRevenueDkk,
        double totalCostDkk,
        Double marginPercent
) {}
