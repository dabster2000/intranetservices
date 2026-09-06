package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * One upcoming birthday among the team's current members (JK Team 2.0 WP6 §4.6.6, D5).
 * Day and month only — never the year or an age on a management surface.
 *
 * @param daysUntil 0 on the day itself, wrapping across the year boundary
 */
public record TeamBirthdayDTO(
        String userId,
        String firstname,
        String lastname,
        int day,
        int month,
        int daysUntil,
        boolean today
) {
}
