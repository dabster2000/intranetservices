package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.TeamSickLeaveTrackingDTO.MonthlySickDays;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSickLeaveTrackingDTO.SickPeriod;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure, deterministic sick-leave day counting for the Team Dashboard
 * (Funktionærloven 120-day rule proxy). No Quarkus, no DB — unit-testable in isolation.
 *
 * <p><b>Counting rule (as documented to leads/HR):</b>
 * <ol>
 *   <li><b>Fractional days.</b> Each day on which sick hours were registered counts as
 *       {@code min(sick_hours / scheduled_hours, 1.0)} of a sick day — a half-day of
 *       sickness is 0.5, a full scheduled day is 1.0. The scheduled length is the day's
 *       {@code gross_available_hours} (allocation ÷ 5), falling back to a
 *       {@value #STANDARD_WORK_DAY_HOURS}h full-time day when availability is unknown.
 *       (The previous implementation counted every sick row as a flat 1.0 regardless of
 *       hours, over-counting partial days.)</li>
 *   <li><b>Bridging.</b> A run of non-working calendar days (weekends / zero-availability
 *       days) between two <em>full</em> sick days is counted as sick, so a continuous
 *       sickness period across a weekend is not artificially split. A gap is bridged
 *       <em>only</em> when every day in it is genuinely non-working for that user
 *       ({@code gross_available_hours = 0}, or a calendar weekend when no fact row exists)
 *       <em>and</em> carries no registered work. Working weekdays are never bridged — if
 *       the employee was back at work (or the day is an ordinary weekday), the sick period
 *       ends. Bridged days count as a full 1.0 each.</li>
 * </ol>
 *
 * <p>The rolling total is the sum of fractional sick days plus bridged non-working days.
 * Monthly trend distributes each (fractional or bridged) day to the month it falls in.
 *
 * <p><b>Legal basis (Funktionærloven § 5, stk. 2 — the 120-day rule).</b> The counting
 * mirrors established Danish practice and the two Højesteret rulings of Nov 2017 on partial
 * sick leave:
 * <ul>
 *   <li><b>Full-time sickness:</b> Sundays, public holidays and weekends that fall inside a
 *       continuous full-sick period count as sick days (the "syg fredag og igen mandag →
 *       weekenden tæller med" rule) — modelled here by bridging only when both boundary days
 *       are <em>full</em> sick days.</li>
 *   <li><b>Partial sickness (deltidssygemeldt):</b> only the actual hours of absence count,
 *       proportionally, and non-working days are <em>not</em> added (Højesteret rejected the
 *       earlier averaging method) — modelled by the fractional day and by never bridging
 *       around a partial day.</li>
 * </ul>
 *
 * <p><b>Deliberately conservative limitations</b> (both under-count, never over-count, so the
 * figure cannot falsely push someone over the 120-day line; see the UI disclaimer):
 * <ul>
 *   <li>If an employee offers a partial return that the employer <em>rejects</em>, the law
 *       counts the whole day — that employer decision is not in the data, so we count the
 *       registered hours only.</li>
 *   <li>Public holidays that fall on a weekday keep {@code gross_available_hours > 0} in
 *       {@code fact_user_day}, so they are treated as working days and are not bridged.</li>
 * </ul>
 * This is a decision-support indicator, not a legal determination.
 */
public final class SickLeaveCalculator {

    /** Full-time scheduled day length (37h week ÷ 5). Used only as a fallback denominator. */
    public static final double STANDARD_WORK_DAY_HOURS = 7.4;

    /** Maximum calendar gap (in days) that may be bridged. A normal weekend is 2 days. */
    public static final int MAX_BRIDGE_GAP_DAYS = 3;

    private static final double HOURS_EPSILON = 0.0001;
    private static final double FULL_DAY_EPSILON = 0.001;

    private SickLeaveCalculator() {}

    /**
     * One day of a user's availability facts, as read from {@code fact_user_day}.
     *
     * @param date                    the calendar day
     * @param sickHours               registered sick hours (0 on weekends/non-sick days)
     * @param grossAvailableHours     scheduled hours for the day (0 on weekends/holidays)
     * @param registeredBillableHours billable hours registered that day (the "worked" signal)
     */
    public record DayFact(
            LocalDate date,
            double sickHours,
            double grossAvailableHours,
            double registeredBillableHours
    ) {}

    /** Result of {@link #compute(List)} for a single user. */
    public record UserSickResult(
            List<SickPeriod> periods,
            double rollingTotal,
            List<MonthlySickDays> monthlyTrend
    ) {
        public static UserSickResult empty() {
            return new UserSickResult(List.of(), 0.0, List.of());
        }
    }

    /**
     * Fraction of a scheduled day the user was sick, in [0, 1].
     * Divides by the day's own scheduled length so part-time employees are counted
     * correctly; equals {@code sick_hours / 7.4} for a full-time (37h/week) employee.
     */
    public static double effectiveSickDay(DayFact day) {
        double denom = day.grossAvailableHours() > HOURS_EPSILON
                ? day.grossAvailableHours()
                : STANDARD_WORK_DAY_HOURS;
        return Math.min(day.sickHours() / denom, 1.0);
    }

    /** A day counts as "full" when the whole scheduled day was sick (effective day ≈ 1.0). */
    private static boolean isFullSickDay(double effectiveDay) {
        return effectiveDay >= 1.0 - FULL_DAY_EPSILON;
    }

    /**
     * A gap day is genuinely non-working when its scheduled availability is zero
     * (weekend / holiday / shutdown). When no fact row exists for the day, fall back to a
     * calendar weekend check so a missing weekend row still bridges.
     */
    private static boolean isNonWorkingDay(DayFact day, LocalDate date) {
        if (day != null) {
            return day.grossAvailableHours() <= HOURS_EPSILON;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /** True when any billable work was registered on the day — such a day is never bridged. */
    private static boolean hasRegisteredWork(DayFact day) {
        return day != null && day.registeredBillableHours() > HOURS_EPSILON;
    }

    private static String monthKey(LocalDate date) {
        return String.format("%d-%02d", date.getYear(), date.getMonthValue());
    }

    /**
     * Computes fractional sick-day totals, bridged periods and the monthly trend for one user.
     *
     * @param days all fetched {@code fact_user_day} rows for the user in the window — sick days
     *             plus zero-availability (weekend/holiday) days. Order is not required.
     */
    public static UserSickResult compute(List<DayFact> days) {
        if (days == null || days.isEmpty()) {
            return UserSickResult.empty();
        }

        // Fast lookup for gap-day inspection.
        Map<LocalDate, DayFact> byDate = new LinkedHashMap<>();
        for (DayFact d : days) {
            if (d != null && d.date() != null) {
                byDate.put(d.date(), d);
            }
        }

        // Sick days only, chronologically. Sick hours are always on weekdays (weekends are 0h).
        List<DayFact> sickDays = days.stream()
                .filter(d -> d != null && d.date() != null && d.sickHours() > HOURS_EPSILON)
                .sorted(Comparator.comparing(DayFact::date))
                .toList();

        if (sickDays.isEmpty()) {
            return UserSickResult.empty();
        }

        List<SickPeriod> periods = new ArrayList<>();
        Map<String, Double> monthlyDays = new TreeMap<>();
        double total = 0.0;

        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        double periodDays = 0.0;
        boolean prevWasFull = false;

        for (DayFact sick : sickDays) {
            LocalDate date = sick.date();
            double eff = effectiveSickDay(sick);

            if (periodStart == null) {
                periodStart = date;
                periodEnd = date;
                periodDays = eff;
                monthlyDays.merge(monthKey(date), eff, Double::sum);
                total += eff;
                prevWasFull = isFullSickDay(eff);
                continue;
            }

            long gap = ChronoUnit.DAYS.between(periodEnd, date) - 1;
            boolean bridged = false;

            if (gap >= 1 && gap <= MAX_BRIDGE_GAP_DAYS && prevWasFull && isFullSickDay(eff)) {
                boolean allBridgeable = true;
                for (long g = 1; g <= gap; g++) {
                    LocalDate gapDate = periodEnd.plusDays(g);
                    DayFact gapFact = byDate.get(gapDate);
                    if (!isNonWorkingDay(gapFact, gapDate) || hasRegisteredWork(gapFact)) {
                        allBridgeable = false;
                        break;
                    }
                }
                if (allBridgeable) {
                    bridged = true;
                    for (long g = 1; g <= gap; g++) {
                        LocalDate gapDate = periodEnd.plusDays(g);
                        monthlyDays.merge(monthKey(gapDate), 1.0, Double::sum);
                        total += 1.0;
                        periodDays += 1.0;
                    }
                }
            }

            if (!bridged && gap >= 1) {
                // The sick period ended (return to work, ordinary weekday, or too-long gap).
                periods.add(new SickPeriod(periodStart, periodEnd, periodDays));
                periodStart = date;
                periodDays = 0.0;
            }

            periodEnd = date;
            periodDays += eff;
            monthlyDays.merge(monthKey(date), eff, Double::sum);
            total += eff;
            prevWasFull = isFullSickDay(eff);
        }

        if (periodStart != null) {
            periods.add(new SickPeriod(periodStart, periodEnd, periodDays));
        }

        List<MonthlySickDays> trend = monthlyDays.entrySet().stream()
                .map(e -> new MonthlySickDays(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(MonthlySickDays::month))
                .toList();

        return new UserSickResult(periods, total, trend);
    }
}
