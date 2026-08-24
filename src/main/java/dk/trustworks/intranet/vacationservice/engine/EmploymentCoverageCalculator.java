package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a userstatus timeline into per-month employment coverage (0..1) for
 * vacation accrual: the fraction of the month's calendar days spent in a
 * paid, accruing status. Vacation accrues during employment with pay
 * (including paid/maternity leave) but not during unpaid leave — and never
 * for external consultants.
 */
public final class EmploymentCoverageCalculator {

    private static final Set<StatusType> ACCRUING_STATUSES =
            EnumSet.of(StatusType.ACTIVE, StatusType.PAID_LEAVE, StatusType.MATERNITY_LEAVE);
    private static final Set<ConsultantType> ACCRUING_TYPES =
            EnumSet.of(ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT);

    /** One status change; effective from {@code from} until the next interval's {@code from}. */
    public record StatusInterval(LocalDate from, StatusType status, ConsultantType type) {
    }

    private EmploymentCoverageCalculator() {
    }

    public static boolean accrues(StatusType status, ConsultantType type) {
        return status != null && type != null
                && ACCRUING_STATUSES.contains(status) && ACCRUING_TYPES.contains(type);
    }

    /**
     * @return map keyed by first-of-month with coverage 0..1; months with zero
     * coverage are included so callers can distinguish "known zero" from
     * "outside the range".
     */
    public static Map<LocalDate, Double> coverageByMonth(List<StatusInterval> timeline,
                                                         YearMonth from, YearMonth to) {
        List<StatusInterval> sorted = timeline.stream()
                .sorted(Comparator.comparing(StatusInterval::from))
                .toList();

        Map<LocalDate, Double> coverage = new LinkedHashMap<>();
        YearMonth month = from;
        while (!month.isAfter(to)) {
            LocalDate monthStart = month.atDay(1);
            LocalDate monthEnd = month.atEndOfMonth();
            int coveredDays = 0;
            for (int i = 0; i < sorted.size(); i++) {
                StatusInterval interval = sorted.get(i);
                if (!accrues(interval.status(), interval.type())) continue;
                LocalDate intervalStart = interval.from();
                LocalDate intervalEnd = i + 1 < sorted.size() ? sorted.get(i + 1).from().minusDays(1) : LocalDate.MAX;
                LocalDate overlapStart = intervalStart.isAfter(monthStart) ? intervalStart : monthStart;
                LocalDate overlapEnd = intervalEnd.isBefore(monthEnd) ? intervalEnd : monthEnd;
                if (!overlapStart.isAfter(overlapEnd)) {
                    coveredDays += (int) (overlapEnd.toEpochDay() - overlapStart.toEpochDay() + 1);
                }
            }
            coverage.put(monthStart, coveredDays == 0 ? 0.0
                    : Math.min(1.0, coveredDays / (double) month.lengthOfMonth()));
            month = month.plusMonths(1);
        }
        return coverage;
    }
}
