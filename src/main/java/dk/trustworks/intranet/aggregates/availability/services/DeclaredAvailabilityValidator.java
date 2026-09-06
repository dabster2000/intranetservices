package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityCopyRequest;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityUpsertRequest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure validation for declared-availability writes (spec §4.1.5). Returns every problem in
 * one list so a bulk {@code PUT} is refused as a whole before a single row is touched —
 * the batch is all-or-nothing by construction, not only by transaction rollback.
 */
public final class DeclaredAvailabilityValidator {

    public static final BigDecimal MIN_HOURS = BigDecimal.ZERO;
    public static final BigDecimal MAX_HOURS = new BigDecimal("24");
    /** Declarations are quarter-hours: 0.25 steps. */
    public static final BigDecimal STEP = new BigDecimal("0.25");
    public static final int MAX_NOTE_LENGTH = 255;
    /** Upper bound on one batch — a year and a bit of days; more is a runaway client. */
    public static final int MAX_BATCH_SIZE = 400;
    public static final int MAX_COPY_TARGETS = 12;

    private DeclaredAvailabilityValidator() {
    }

    /**
     * Problems with an upsert batch; empty means valid.
     *
     * <p>An item whose {@code hours} is {@code null} is a <em>clear</em>: the day's declaration
     * is removed and the day becomes unplanned again. It needs no hours validation and its note
     * is ignored, so a planner can send additions, changes and removals in one request.
     */
    public static List<String> validateUpsert(List<DeclaredAvailabilityUpsertRequest> items) {
        List<String> problems = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            problems.add("At least one day is required");
            return problems;
        }
        if (items.size() > MAX_BATCH_SIZE) {
            problems.add("At most " + MAX_BATCH_SIZE + " days per request");
            return problems;
        }
        Set<LocalDate> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            DeclaredAvailabilityUpsertRequest item = items.get(i);
            String where = "item " + (i + 1);
            if (item == null) {
                problems.add(where + ": missing");
                continue;
            }
            if (item.day() == null) {
                problems.add(where + ": day is required");
            } else if (!seen.add(item.day())) {
                problems.add(where + ": " + item.day() + " appears more than once");
            }
            if (item.hours() == null) {
                continue; // a clear — nothing else to check
            }
            String hoursProblem = validateHours(item.hours());
            if (hoursProblem != null) {
                problems.add(where + ": " + hoursProblem);
            }
            if (item.note() != null && item.note().length() > MAX_NOTE_LENGTH) {
                problems.add(where + ": note must be at most " + MAX_NOTE_LENGTH + " characters");
            }
        }
        return problems;
    }

    /** {@code null} when valid, otherwise the reason. */
    public static String validateHours(BigDecimal hours) {
        if (hours == null) {
            return "hours is required";
        }
        if (hours.compareTo(MIN_HOURS) < 0 || hours.compareTo(MAX_HOURS) > 0) {
            return "hours must be between 0 and 24";
        }
        // Quarter-hour steps: hours / 0.25 must be a whole number.
        BigDecimal quarters = hours.divide(STEP);
        if (quarters.stripTrailingZeros().scale() > 0) {
            return "hours must be a multiple of 0.25";
        }
        return null;
    }

    /** Problems with a copy-forward request; empty means valid. */
    public static List<String> validateCopy(DeclaredAvailabilityCopyRequest request) {
        List<String> problems = new ArrayList<>();
        if (request == null) {
            problems.add("A request body is required");
            return problems;
        }
        LocalDate source = request.sourceWeekStart();
        if (source == null) {
            problems.add("sourceWeekStart is required");
        } else if (source.getDayOfWeek() != DayOfWeek.MONDAY) {
            problems.add("sourceWeekStart must be a Monday");
        }
        List<LocalDate> targets = request.targetWeekStarts();
        if (targets == null || targets.isEmpty()) {
            problems.add("At least one target week is required");
            return problems;
        }
        if (targets.size() > MAX_COPY_TARGETS) {
            problems.add("At most " + MAX_COPY_TARGETS + " target weeks per request");
        }
        Set<LocalDate> seen = new HashSet<>();
        for (LocalDate target : targets) {
            if (target == null) {
                problems.add("A target week is missing");
            } else if (target.getDayOfWeek() != DayOfWeek.MONDAY) {
                problems.add(target + " is not a Monday");
            } else if (source != null && target.equals(source)) {
                problems.add("The source week cannot be a target");
            } else if (!seen.add(target)) {
                problems.add(target + " appears more than once");
            }
        }
        return problems;
    }
}
