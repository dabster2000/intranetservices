package dk.trustworks.intranet.aggregates.availability.dto;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One {@code fact_user_day} row as {@code GET /users/{useruuid}/availabilities/days}
 * returns it (JK Team 2.0 WP1/WP2).
 *
 * <p>Field names mirror the {@link BiDataPerDay} entity the endpoint used to serialise
 * directly, so the one consumer ({@code /api/availability} in the BFF) reads the same JSON.
 * Two things changed: the heavy {@code User} / {@code Company} graphs are reduced to the
 * references the timesheet actually uses, and two fields were added —
 *
 * <ul>
 *   <li>{@code declaredHours}: the declaration for that day, or {@code null} when none —
 *       lets the timesheet apply the capacity-relative leave rule only on declared days.</li>
 *   <li>{@code hourlyPaid}: whether the person's salary on that day is {@code HOURLY};
 *       only populated when the caller is the subject, and used to keep the flat-7.4 h
 *       "Book time off" flow away from hourly staff (spec §4.1.3).</li>
 * </ul>
 *
 * Built <em>after</em> the cached service call: the cached entity list is never mutated, so
 * the per-caller {@code hourlyPaid} can never leak across actors through the cache.
 */
public record AvailabilityDayDTO(
        LocalDate documentDate,
        Integer year,
        Integer month,
        Integer day,
        UserRef user,
        CompanyRef company,
        BigDecimal grossAvailableHours,
        BigDecimal unavailableHours,
        BigDecimal vacationHours,
        BigDecimal sickHours,
        BigDecimal maternityLeaveHours,
        BigDecimal nonPaydLeaveHours,
        BigDecimal paidLeaveHours,
        String consultantType,
        String statusType,
        BigDecimal declaredHours,
        Boolean hourlyPaid
) {
    public record UserRef(String uuid, String username, String firstname, String lastname, String email) {}

    public record CompanyRef(String uuid, String name) {}

    public static AvailabilityDayDTO from(BiDataPerDay row, BigDecimal declaredHours, Boolean hourlyPaid) {
        return new AvailabilityDayDTO(
                row.documentDate,
                row.year,
                row.month,
                row.day,
                row.user == null ? null : new UserRef(row.user.getUuid(), row.user.getUsername(),
                        row.user.getFirstname(), row.user.getLastname(), row.user.getEmail()),
                row.company == null ? null : new CompanyRef(row.company.getUuid(), row.company.getName()),
                row.grossAvailableHours,
                row.unavailableHours,
                row.vacationHours,
                row.sickHours,
                row.maternityLeaveHours,
                row.nonPaydLeaveHours,
                row.paidLeaveHours,
                row.consultantType == null ? null : row.consultantType.name(),
                row.statusType == null ? null : row.statusType.name(),
                declaredHours,
                hourlyPaid
        );
    }
}
