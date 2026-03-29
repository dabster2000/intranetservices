package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Monthly absence overview for a team member, broken down by absence type.
 * Used to build stacked bar charts showing the trailing 6 months of absence.
 *
 * <p>Hours are aggregated from {@code fact_user_day} columns:
 * vacation_hours, sick_hours, maternity_leave_hours, non_payd_leave_hours.
 */
public record TeamAbsenceOverviewDTO(
        /** Month label for display (e.g., "Oct 2025") */
        String monthLabel,
        /** Month key in format YYYY-MM (e.g., "2025-10") */
        String monthKey,
        int year,
        int month,
        /** Total vacation hours for the team in this month */
        double vacationHours,
        /** Total sick hours for the team in this month */
        double sickHours,
        /** Total maternity leave hours for the team in this month */
        double maternityHours,
        /** Total other leave hours (non-paid leave + paid leave) for the team in this month */
        double otherLeaveHours
) {}
