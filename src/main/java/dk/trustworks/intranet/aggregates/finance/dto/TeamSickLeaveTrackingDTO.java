package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Sick leave tracking data for a single team member.
 * Includes the current rolling 365-day total, threshold status (Funktionaerloven 120-day rule),
 * monthly trend, and consecutive sick periods.
 *
 * <p>Data sourced from {@code fact_sick_day_rolling_mat}.
 *
 * <p>Threshold statuses:
 * <ul>
 *   <li>{@code OK} — rolling total &lt; 80 days</li>
 *   <li>{@code WARNING} — rolling total 80–99 days</li>
 *   <li>{@code CRITICAL} — rolling total &ge; 100 days</li>
 * </ul>
 */
public record TeamSickLeaveTrackingDTO(
        String userId,
        String firstname,
        String lastname,
        /** Current rolling 365-day sick day total */
        double currentRollingTotal,
        /** Threshold status: OK, WARNING, or CRITICAL */
        String thresholdStatus,
        /** Monthly sick day totals for the trailing 12 months */
        List<MonthlySickDays> trend,
        /** Consecutive sick day periods (stretches of effective_sick_day > 0) */
        List<SickPeriod> sickPeriods
) {

    /**
     * Monthly aggregation of sick days for trend display.
     */
    public record MonthlySickDays(
            /** Month key in format YYYY-MM (e.g., "2025-10") */
            String month,
            /** Total effective sick days in this month */
            double sickDays
    ) {}

    /**
     * A consecutive stretch of sick days (including bridged weekends).
     */
    public record SickPeriod(
            LocalDate startDate,
            LocalDate endDate,
            /** Total effective sick days in this period */
            double totalDays
    ) {}
}
