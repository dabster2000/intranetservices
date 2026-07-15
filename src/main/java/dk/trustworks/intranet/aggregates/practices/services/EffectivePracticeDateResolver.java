package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Resolves practice membership from immutable, half-open effective-date intervals.
 *
 * <p>History is authoritative from its first covered date.  The current practice is used only
 * before that boundary; a gap or overlap inside covered history is an integrity failure rather
 * than an invitation to fabricate a recipient.</p>
 */
@ApplicationScoped
public class EffectivePracticeDateResolver {

    public Resolution resolve(
            LocalDate date,
            List<HistoryInterval> sourceIntervals,
            String currentPractice) {
        Objects.requireNonNull(date, "date");
        List<HistoryInterval> intervals = validated(sourceIntervals);
        if (intervals.isEmpty() || date.isBefore(intervals.getFirst().effectiveFrom())) {
            return currentPractice == null || currentPractice.isBlank()
                    ? Resolution.unavailable(Reason.CURRENT_PRACTICE_UNAVAILABLE)
                    : Resolution.fallback(currentPractice.trim(), Reason.BEFORE_HISTORY_COVERAGE);
        }

        for (HistoryInterval interval : intervals) {
            if (!date.isBefore(interval.effectiveFrom())
                    && (interval.effectiveToExclusive() == null
                    || date.isBefore(interval.effectiveToExclusive()))) {
                return Resolution.history(interval.practice(), interval.effectiveFrom());
            }
        }
        return Resolution.unavailable(Reason.HISTORY_GAP);
    }

    public List<HistoryInterval> validated(List<HistoryInterval> sourceIntervals) {
        if (sourceIntervals == null || sourceIntervals.isEmpty()) return List.of();
        List<HistoryInterval> sorted = new ArrayList<>(sourceIntervals);
        sorted.sort(Comparator.comparing(HistoryInterval::effectiveFrom)
                .thenComparing(HistoryInterval::practice));
        HistoryInterval previous = null;
        for (HistoryInterval current : sorted) {
            Objects.requireNonNull(current, "history interval");
            if (current.effectiveFrom() == null || current.practice() == null
                    || current.practice().isBlank()) {
                throw new InvalidPracticeHistoryException(Reason.INVALID_INTERVAL);
            }
            if (current.effectiveToExclusive() != null
                    && !current.effectiveFrom().isBefore(current.effectiveToExclusive())) {
                throw new InvalidPracticeHistoryException(Reason.INVALID_INTERVAL);
            }
            if (previous != null && (previous.effectiveToExclusive() == null
                    || previous.effectiveToExclusive().isAfter(current.effectiveFrom()))) {
                throw new InvalidPracticeHistoryException(Reason.OVERLAPPING_HISTORY);
            }
            previous = current;
        }
        return List.copyOf(sorted);
    }

    public record HistoryInterval(
            LocalDate effectiveFrom,
            LocalDate effectiveToExclusive,
            String practice,
            String sourceEvidence) {
    }

    public record Resolution(
            Status status,
            String practice,
            LocalDate effectiveBasisFrom,
            Reason reason) {
        static Resolution history(String practice, LocalDate from) {
            return new Resolution(Status.HISTORY, practice, from, null);
        }

        static Resolution fallback(String practice, Reason reason) {
            return new Resolution(Status.CURRENT_PRACTICE_FALLBACK, practice, null, reason);
        }

        static Resolution unavailable(Reason reason) {
            return new Resolution(Status.UNAVAILABLE, null, null, reason);
        }

        public boolean available() {
            return status != Status.UNAVAILABLE;
        }
    }

    public enum Status { HISTORY, CURRENT_PRACTICE_FALLBACK, UNAVAILABLE }

    public enum Reason {
        BEFORE_HISTORY_COVERAGE,
        CURRENT_PRACTICE_UNAVAILABLE,
        HISTORY_GAP,
        OVERLAPPING_HISTORY,
        INVALID_INTERVAL
    }

    public static final class InvalidPracticeHistoryException extends IllegalArgumentException {
        private final Reason reason;

        public InvalidPracticeHistoryException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
