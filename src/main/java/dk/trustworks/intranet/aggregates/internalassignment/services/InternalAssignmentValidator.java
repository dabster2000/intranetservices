package dk.trustworks.intranet.aggregates.internalassignment.services;

import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure validation and sizing for internal assignments (spec §4.3.1–4.3.2, D9).
 *
 * <pre>
 *   hours_per_week = total ÷ weekdays-in-period × 5       (when sized by total)
 * </pre>
 */
public final class InternalAssignmentValidator {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_NOTES_LENGTH = 4000;
    public static final BigDecimal MAX_HOURS_PER_WEEK = new BigDecimal("168");
    public static final BigDecimal MAX_TOTAL_HOURS = new BigDecimal("99999");
    /** Longest period one assignment may span — two years. */
    public static final int MAX_PERIOD_DAYS = 731;

    private InternalAssignmentValidator() {
    }

    /** What a request resolves to once validated. */
    public record Sizing(BigDecimal hoursPerWeek, BigDecimal estimatedTotalHours) {}

    /** Problems with a request; empty means valid. */
    public static List<String> validate(InternalAssignmentRequest request) {
        List<String> problems = new ArrayList<>();
        if (request == null) {
            problems.add("A request body is required");
            return problems;
        }
        if (request.title() == null || request.title().isBlank()) {
            problems.add("title is required");
        } else if (request.title().trim().length() > MAX_TITLE_LENGTH) {
            problems.add("title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        if (request.activeFrom() == null) {
            problems.add("activeFrom is required");
        }
        if (request.activeTo() == null) {
            problems.add("activeTo is required");
        }
        if (request.activeFrom() != null && request.activeTo() != null) {
            if (request.activeTo().isBefore(request.activeFrom())) {
                problems.add("activeTo must not be before activeFrom");
            } else if (request.activeFrom().plusDays(MAX_PERIOD_DAYS).isBefore(request.activeTo())) {
                problems.add("The period must be at most two years");
            }
        }
        boolean byWeek = request.hoursPerWeek() != null;
        boolean byTotal = request.totalHours() != null;
        if (byWeek == byTotal) {
            problems.add("Give exactly one of hoursPerWeek or totalHours");
        }
        if (byWeek) {
            if (request.hoursPerWeek().signum() < 0) {
                problems.add("hoursPerWeek must not be negative");
            } else if (request.hoursPerWeek().compareTo(MAX_HOURS_PER_WEEK) > 0) {
                problems.add("hoursPerWeek must be at most " + MAX_HOURS_PER_WEEK);
            }
        }
        if (byTotal) {
            if (request.totalHours().signum() < 0) {
                problems.add("totalHours must not be negative");
            } else if (request.totalHours().compareTo(MAX_TOTAL_HOURS) > 0) {
                problems.add("totalHours must be at most " + MAX_TOTAL_HOURS);
            }
        }
        if (request.notes() != null && request.notes().length() > MAX_NOTES_LENGTH) {
            problems.add("notes must be at most " + MAX_NOTES_LENGTH + " characters");
        }
        return problems;
    }

    /** The canonical sizing of a request that passed {@link #validate}. */
    public static Sizing sizingOf(InternalAssignmentRequest request) {
        if (request.hoursPerWeek() != null) {
            return new Sizing(request.hoursPerWeek().setScale(2, RoundingMode.HALF_UP), null);
        }
        BigDecimal total = request.totalHours().setScale(2, RoundingMode.HALF_UP);
        return new Sizing(hoursPerWeekFromTotal(total, request.activeFrom(), request.activeTo()), total);
    }

    /**
     * {@code total ÷ weekdays × 5}, rounded to two decimals. A period with no weekdays (a
     * weekend-only span) cannot be spread and yields 0 hours per week.
     */
    public static BigDecimal hoursPerWeekFromTotal(BigDecimal total, LocalDate from, LocalDate to) {
        int weekdays = weekdaysInclusive(from, to);
        if (weekdays == 0 || total == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return total.multiply(BigDecimal.valueOf(5))
                .divide(BigDecimal.valueOf(weekdays), 2, RoundingMode.HALF_UP);
    }

    /** Mon–Fri days in {@code [from, to]}, both ends included. */
    public static int weekdaysInclusive(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return 0;
        }
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }
}
