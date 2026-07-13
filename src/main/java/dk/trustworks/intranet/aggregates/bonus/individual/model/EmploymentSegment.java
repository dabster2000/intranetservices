package dk.trustworks.intranet.aggregates.bonus.individual.model;

import java.time.LocalDate;

/** One continuous employed span with a single company and weekly allocation. */
public record EmploymentSegment(
        LocalDate from,
        LocalDate to,
        String companyUuid,
        int weeklyAllocation
) {
    public EmploymentSegment {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Employment segment requires from <= to");
        }
    }

    public DateInterval interval() {
        return new DateInterval(from, to);
    }
}
