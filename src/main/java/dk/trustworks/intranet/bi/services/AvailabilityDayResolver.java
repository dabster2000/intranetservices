package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.utils.DateUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The pure core of {@link UserAvailabilityCalculatorService}: how many hours a person is
 * rostered for on a day, and how many of those the company's full-time conventions take
 * away again (spec §4.1.3, D7).
 *
 * <pre>
 *   not declaring : fullAvailability = allocation / 5, weekends 0
 *                   unavailable      = Friday 2 h, first Thu/Fri of October 7.4 h (min'd)
 *   declaring     : fullAvailability = declared ?? 0   (a declared Saturday is honoured)
 *                   unavailable      = 0               (both deductions encode a 37 h week)
 * </pre>
 *
 * The leave clamps ({@code Math.min(fullAvailability, …)} for vacation / sickness /
 * maternity) stay in the service untouched: they now clamp against the rostered day,
 * which is precisely the fix. Kept free of CDI so the fallback matrix is a plain unit test.
 */
public final class AvailabilityDayResolver {

    /** What the resolver decided for one day. */
    public record DayAvailability(double fullAvailability, double unavailableHours) {}

    private AvailabilityDayResolver() {
    }

    /**
     * @param declaring        the answer from {@code DeclaredAvailabilityPolicy.isDeclaring}
     * @param weeklyAllocation {@code userstatus.allocation} — hours per week
     * @param day              the day being resolved
     * @param declaredHours    the declaration for {@code day}, or {@code null} for no row.
     *                         Only consulted when {@code declaring} is true.
     */
    public static DayAvailability resolve(boolean declaring, int weeklyAllocation, LocalDate day,
                                          BigDecimal declaredHours) {
        if (declaring) {
            double declared = declaredHours == null ? 0.0 : declaredHours.doubleValue();
            // An undeclared day, weekday or weekend, is not available (D7). The Friday and
            // October deductions are a 37-hour convention and do not apply to a declared week.
            return new DayAvailability(Math.max(0.0, declared), 0.0);
        }

        // Today's code path, byte for byte, for everyone who is not declaring.
        double fullAvailability = weeklyAllocation / 5.0;
        if (DateUtils.isWeekend(day)) fullAvailability = 0.0;

        double unavailableHours = DateUtils.isFriday(day) ? Math.min(2.0, fullAvailability) : 0.0;
        unavailableHours = DateUtils.isFirstThursdayOrFridayInOctober(day)
                ? Math.min(7.4, fullAvailability)
                : unavailableHours;
        return new DayAvailability(fullAvailability, unavailableHours);
    }
}
