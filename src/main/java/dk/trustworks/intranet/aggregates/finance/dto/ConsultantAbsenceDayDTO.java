package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;

/**
 * Daily absence record for a single consultant.
 * Used in the KPC tab's Leave Timeline to show what type of leave
 * occurred on each individual day.
 *
 * <p>Only days with at least one non-zero absence column are returned.
 * Hours come directly from {@code fact_user_day} columns.
 */
public record ConsultantAbsenceDayDTO(
        /** The calendar date of the absence */
        LocalDate documentDate,
        /** Vacation hours on this day */
        double vacationHours,
        /** Sick leave hours on this day */
        double sickHours,
        /** Maternity leave hours on this day */
        double maternityHours,
        /** Paid leave hours on this day */
        double paidLeaveHours,
        /** Non-paid leave hours on this day (column: non_payd_leave_hours) */
        double nonPaidLeaveHours
) {}
