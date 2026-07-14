package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.PracticeForecastPeriodType;

import java.time.LocalDate;
import java.time.YearMonth;

/** Pure date and hour calculations for the practice actual/budget series. */
final class PracticeForecastCalculator {

    private PracticeForecastCalculator() {}

    static ForecastWindow window(LocalDate copenhagenToday, LocalDate actualDataThroughDate) {
        YearMonth current = YearMonth.from(copenhagenToday);
        LocalDate requestedActualThroughDate = copenhagenToday.minusDays(1);
        LocalDate effectiveActualThroughDate = actualDataThroughDate == null
                ? null
                : actualDataThroughDate.isAfter(requestedActualThroughDate)
                        ? requestedActualThroughDate
                        : actualDataThroughDate;
        return new ForecastWindow(
                current,
                current.minusMonths(6),
                current.plusMonths(5),
                current.minusMonths(6).atDay(1),
                effectiveActualThroughDate,
                current.atDay(1),
                current.plusMonths(5).atEndOfMonth()
        );
    }

    static PracticeForecastPeriodType periodType(YearMonth month, YearMonth current) {
        if (month.isBefore(current)) return PracticeForecastPeriodType.COMPLETED_ACTUAL;
        if (month.equals(current)) return PracticeForecastPeriodType.CURRENT_MTD;
        return PracticeForecastPeriodType.FORWARD_BUDGET;
    }

    static LocalDate actualThroughDate(YearMonth month, ForecastWindow window) {
        if (window.actualToDate() == null) return null;
        if (month.isBefore(window.currentMonth())) {
            return !window.actualToDate().isBefore(month.atEndOfMonth())
                    ? month.atEndOfMonth()
                    : null;
        }
        if (month.equals(window.currentMonth()) && !window.actualToDate().isBefore(month.atDay(1))) {
            return window.actualToDate();
        }
        return null;
    }

    static Double utilizationPct(double numeratorHours, double denominatorHours) {
        return denominatorHours > 0.0 ? numeratorHours / denominatorHours * 100.0 : null;
    }

    static Double gapHours(double numeratorHours, double denominatorHours, double targetPct) {
        if (denominatorHours <= 0.0) return null;
        return Math.max((targetPct / 100.0) * denominatorHours - numeratorHours, 0.0);
    }

    record ForecastWindow(
            YearMonth currentMonth,
            YearMonth outputStartMonth,
            YearMonth outputEndMonth,
            LocalDate actualFromDate,
            LocalDate actualToDate,
            LocalDate budgetFromDate,
            LocalDate budgetToDate
    ) {}
}
