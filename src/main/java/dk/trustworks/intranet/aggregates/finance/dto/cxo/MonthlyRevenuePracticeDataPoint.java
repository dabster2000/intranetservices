package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 *
 * <p>The compact constructor wraps {@code practiceRevenue} in an unmodifiable
 * {@link LinkedHashMap} copy to preserve the caller's insertion order (the service
 * builds it ordered to match the {@code practices} list) while preventing external
 * mutation of the DTO's internal state.</p>
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
) {
    public MonthlyRevenuePracticeDataPoint {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
        // Defensive copy preserves insertion order (LinkedHashMap) while preventing
        // external mutation. Map.copyOf would lose ordering and reject null values
        // (the service uses 0.0 for missing practices, never null, so order is the
        // only practical concern).
        practiceRevenue = practiceRevenue == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(practiceRevenue));
    }
}
