package dk.trustworks.intranet.aggregates.bonus.individual.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Inclusive date interval used for non-contiguous monthly employment overlaps. */
public record DateInterval(LocalDate from, LocalDate to) {
    public DateInterval {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Date interval requires from <= to");
        }
    }

    public int days() {
        return Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }
}
