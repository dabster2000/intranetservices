package dk.trustworks.intranet.apigateway.support;

import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerLevelMultiplier;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure utility (no CDI/DB) that resolves a user's career level — and therefore
 * their bonus multiplier — for each fiscal month, using MONTH-END timing
 * (§2.2, override (d) of the career-level-multipliers spec).
 *
 * <p>Fiscal month index {@code m} (0=Jul .. 11=Jun) of fiscal-start-year {@code FY}:
 * <pre>
 *   calMonth = m &lt; 6 ? 7 + m : m - 5
 *   calYear  = m &lt; 6 ? FY    : FY + 1
 *   monthEnd = YearMonth.of(calYear, calMonth).atEndOfMonth()
 *   level    = UserCareerLevel with the greatest activeFrom ≤ monthEnd (else null → 1×)
 * </pre>
 */
public final class CareerMultiplierResolver {

    private CareerMultiplierResolver() {
    }

    /** Display-only representative result: the chosen level name and its multiplier. */
    public record Representative(String levelName, double multiplier) {
    }

    /**
     * Maps a fiscal month index (0=Jul .. 11=Jun) to a calendar {calYear, calMonth}.
     */
    public static int[] fiscalMonthToCalendar(int fiscalStartYear, int monthIndex) {
        int calMonth = monthIndex < 6 ? 7 + monthIndex : monthIndex - 5;
        int calYear = monthIndex < 6 ? fiscalStartYear : fiscalStartYear + 1;
        return new int[]{calYear, calMonth};
    }

    /**
     * Returns the records sorted by {@code activeFrom} ascending.
     * Records with a {@code null} activeFrom are dropped.
     */
    public static List<UserCareerLevel> sortAscending(List<UserCareerLevel> records) {
        List<UserCareerLevel> sorted = new ArrayList<>();
        if (records != null) {
            for (UserCareerLevel record : records) {
                if (record != null && record.getActiveFrom() != null) {
                    sorted.add(record);
                }
            }
        }
        sorted.sort(Comparator.comparing(UserCareerLevel::getActiveFrom));
        return sorted;
    }

    /**
     * Resolves the active career level at the end of the given calendar month.
     *
     * <p>Iterates the ascending-sorted list, keeping the latest record whose
     * {@code activeFrom} ≤ monthEnd, and breaks once a record's activeFrom is
     * after monthEnd. Returns {@code null} when no record applies.
     */
    public static CareerLevel levelAtMonthEnd(List<UserCareerLevel> sortedAsc, int calYear, int calMonth) {
        LocalDate monthEnd = YearMonth.of(calYear, calMonth).atEndOfMonth();
        CareerLevel current = null;
        if (sortedAsc != null) {
            for (UserCareerLevel record : sortedAsc) {
                LocalDate activeFrom = record.getActiveFrom();
                if (activeFrom == null) {
                    continue;
                }
                if (activeFrom.isAfter(monthEnd)) {
                    break;
                }
                current = record.getCareerLevel();
            }
        }
        return current;
    }

    /**
     * Returns the 12 month-end multipliers (fiscal order, Jul..Jun).
     */
    public static double[] monthlyMultipliers(List<UserCareerLevel> sortedAsc, int fiscalStartYear) {
        double[] multipliers = new double[12];
        for (int m = 0; m < 12; m++) {
            int[] cal = fiscalMonthToCalendar(fiscalStartYear, m);
            CareerLevel level = levelAtMonthEnd(sortedAsc, cal[0], cal[1]);
            multipliers[m] = CareerLevelMultiplier.of(level);
        }
        return multipliers;
    }

    /**
     * Returns the 12 month-end level names (fiscal order, Jul..Jun).
     * Uses {@link CareerLevel#name()}; an empty string {@code ""} when no level applies.
     */
    public static String[] monthlyLevelNames(List<UserCareerLevel> sortedAsc, int fiscalStartYear) {
        String[] names = new String[12];
        for (int m = 0; m < 12; m++) {
            int[] cal = fiscalMonthToCalendar(fiscalStartYear, m);
            CareerLevel level = levelAtMonthEnd(sortedAsc, cal[0], cal[1]);
            names[m] = level == null ? "" : level.name();
        }
        return names;
    }

    /**
     * Computes the display-only representative (level name, multiplier) per §3:
     * <ol>
     *   <li>Find the latest month with weight &gt; 0. If its multiplier &gt; 0, use
     *       (levelName, multiplier) for that month.</li>
     *   <li>Otherwise, fall back to the latest month with weight &gt; 0 AND
     *       multiplier &gt; 0.</li>
     *   <li>If none qualifies, return ("", 0.0) (= careerIneligible).</li>
     * </ol>
     */
    public static Representative representative(double[] monthWeights,
                                               double[] monthMultipliers,
                                               String[] monthLevelNames) {
        // Latest month with weight > 0.
        int latestWeighted = -1;
        for (int m = monthWeights.length - 1; m >= 0; m--) {
            if (monthWeights[m] > 0) {
                latestWeighted = m;
                break;
            }
        }
        if (latestWeighted >= 0 && monthMultipliers[latestWeighted] > 0) {
            return new Representative(monthLevelNames[latestWeighted], monthMultipliers[latestWeighted]);
        }
        // Fall back to the latest month with weight > 0 AND multiplier > 0.
        for (int m = monthWeights.length - 1; m >= 0; m--) {
            if (monthWeights[m] > 0 && monthMultipliers[m] > 0) {
                return new Representative(monthLevelNames[m], monthMultipliers[m]);
            }
        }
        return new Representative("", 0.0);
    }
}
