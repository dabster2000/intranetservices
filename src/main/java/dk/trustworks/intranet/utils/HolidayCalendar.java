package dk.trustworks.intranet.utils;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Danish public-holiday calendar, as names attached to dates.
 *
 * <p>This is the <em>named</em> equivalent of {@link DateUtils#getVacationDayArray(int)}:
 * the same dates, plus a label. That method is the authoritative definition used
 * everywhere in the backend to decide whether a day is a workday (availability,
 * capacity, sick-leave bridging), so this class must never drift from it —
 * {@code HolidayCalendarTest} pins the two together year by year.</p>
 *
 * <p>Names are in English because the consumer is the English timesheet UI, which
 * uses them to explain why a day inside a booked vacation range was skipped.</p>
 *
 * <p>Two properties of the Trustworks calendar routinely surprise readers:</p>
 * <ul>
 *   <li><b>General Prayer Day (store bededag) exists only before 2024.</b> It was
 *       abolished as a public holiday by law with effect from 2024.</li>
 *   <li><b>24 and 31 December are holidays here</b> even though they are not
 *       statutory Danish public holidays — Trustworks closes both days. Note that
 *       {@code dim_date.is_weekend} in the warehouse disagrees (it marks only
 *       Saturday and Sunday); that divergence is known and out of scope for this
 *       class. Constitution Day (5 June) is <em>not</em> a holiday here.</li>
 * </ul>
 */
public final class HolidayCalendar {

    /** Widest span {@link #between} is ever expected to answer for, in years. */
    public static final int MAX_SPAN_YEARS = 5;

    /**
     * A public holiday: the date, and an English display name for the UI.
     */
    public record Holiday(LocalDate date, String name) {
    }

    private HolidayCalendar() {
    }

    /**
     * Every Trustworks holiday in the given calendar year, sorted by date.
     *
     * <p>The Easter computation below is copied verbatim from
     * {@link DateUtils#getVacationDayArray(int)}, including its idiosyncrasies
     * ({@code Math.round} where the textbook Gauss/Butcher algorithm uses integer
     * division). Those quirks change the result for some years, so "fixing" them
     * here would silently disagree with the availability engine. The whole point
     * of this class is to agree with it; the algorithm is therefore mirrored, not
     * re-derived.</p>
     */
    public static List<Holiday> forYear(int year) {
        int a = year % 19;
        int b = (int) Math.round(year / 100.0);
        int c = year % 100;
        int d = (int) Math.round(b / 4.0);
        int e = b % 4;
        int f = (int) Math.floor((b + 8.0) / 25.0);
        int g = (int) Math.floor((b - f + 1.0) / 3.0);
        int h = (19 * a + b - d - g + 15) % 30;
        int j = (int) Math.floor(c / 4.0);
        int k = c % 4;
        int l = (32 + 2 * e + 2 * j - h - k) % 7;
        int m = (int) Math.floor((a + 11.0 * h + 22.0 * l) / 451.0);
        int n = (int) Math.floor((h + l - 7.0 * m + 114.0) / 31.0);
        int p = (h + l - 7 * m + 114) % 31;

        LocalDate easterDay = LocalDate.of(year, n, p + 1);
        LocalDate christmasEve = LocalDate.of(year, Month.DECEMBER, 24);

        List<Holiday> holidays = new ArrayList<>(13);
        holidays.add(new Holiday(LocalDate.of(year, Month.JANUARY, 1), "New Year's Day"));
        holidays.add(new Holiday(easterDay.minusDays(3), "Maundy Thursday"));
        holidays.add(new Holiday(easterDay.minusDays(2), "Good Friday"));
        holidays.add(new Holiday(easterDay, "Easter Sunday"));
        holidays.add(new Holiday(easterDay.plusDays(1), "Easter Monday"));
        if (year < 2024) holidays.add(new Holiday(easterDay.plusDays(26), "General Prayer Day"));
        holidays.add(new Holiday(easterDay.plusDays(39), "Ascension Day"));
        holidays.add(new Holiday(easterDay.plusDays(49), "Whit Sunday"));
        holidays.add(new Holiday(easterDay.plusDays(50), "Whit Monday"));
        holidays.add(new Holiday(christmasEve, "Christmas Eve"));
        holidays.add(new Holiday(christmasEve.plusDays(1), "Christmas Day"));
        holidays.add(new Holiday(christmasEve.plusDays(2), "Boxing Day"));
        holidays.add(new Holiday(LocalDate.of(year, Month.DECEMBER, 31), "New Year's Eve"));

        holidays.sort(Comparator.comparing(Holiday::date));
        return holidays;
    }

    /**
     * Every holiday between two dates, both ends inclusive, sorted by date.
     *
     * <p>Iterates every calendar year the range touches, so a range spanning New
     * Year still yields December from the first year and January from the second.
     * Returns empty when {@code from} is after {@code to} rather than throwing —
     * a degenerate range simply contains no holidays.</p>
     *
     * @throws IllegalArgumentException if either bound is {@code null}, or the span
     *                                  exceeds {@link #MAX_SPAN_YEARS} — a mistyped
     *                                  year must not turn into thousands of
     *                                  year computations.
     */
    public static List<Holiday> between(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("from and to are required");
        if (from.isAfter(to)) return List.of();
        if (to.getYear() - from.getYear() > MAX_SPAN_YEARS) {
            throw new IllegalArgumentException("Range spans more than " + MAX_SPAN_YEARS + " years: " + from + " to " + to);
        }

        List<Holiday> holidays = new ArrayList<>();
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            for (Holiday holiday : forYear(year)) {
                if (holiday.date().isBefore(from) || holiday.date().isAfter(to)) continue;
                holidays.add(holiday);
            }
        }
        holidays.sort(Comparator.comparing(Holiday::date));
        return holidays;
    }
}
