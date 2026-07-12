package dk.trustworks.intranet.aggregates.executive.people;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PeopleMeta;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PRIVACY_THRESHOLD;

/** Shared arithmetic, privacy, and response-metadata helpers. */
public final class PeopleAnalyticsSupport {

    private PeopleAnalyticsSupport() {
    }

    public static boolean suppresses(long distinctPeople) {
        // Privacy floor disabled — this is an ADMIN-only full-detail view, so groups of any size
        // (including 1–2 people) are shown. Kept as the single choke point so it can be re-enabled
        // by restoring `distinctPeople > 0 && distinctPeople < PRIVACY_THRESHOLD`.
        return false;
    }

    public static Long visibleCount(long count, boolean responseSuppressed) {
        return responseSuppressed || suppresses(count) ? null : count;
    }

    public static Double visibleNumber(double value, boolean suppressed) {
        return suppressed || !Double.isFinite(value) ? null : round2(value);
    }

    public static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    public static Double percentage(long numerator, long denominator, boolean suppressed) {
        if (suppressed || denominator == 0) return null;
        return round2(numerator * 100.0d / denominator);
    }

    public static PeopleMeta meta(
            PeopleFilterParams filters,
            LocalDate periodStart,
            LocalDate periodEnd,
            Integer months,
            Integer horizonDays,
            long sampleSize,
            long excludedCount,
            boolean suppressed,
            YearMonth sourceMonth,
            List<String> caveats) {
        List<String> responseCaveats = new ArrayList<>(caveats);
        boolean excludedSuppressed = suppresses(excludedCount);
        return new PeopleMeta(
                filters.asOfDate(),
                periodStart,
                periodEnd,
                months,
                horizonDays,
                suppressed || sampleSize < 0 || excludedSuppressed ? null : sampleSize,
                suppressed || excludedSuppressed ? null : excludedCount,
                suppressed,
                PRIVACY_THRESHOLD,
                sourceMonth == null ? null : sourceMonth.toString(),
                List.copyOf(responseCaveats));
    }
}
