package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Sick leave in hours for one member (JK Team 2.0 WP6 tab matrix, People / HOURLY): the
 * count, not the 120-day threshold — that line is a funktionær rule with no meaning for an
 * hourly contract.
 */
public record TeamSickHoursDTO(
        String userId,
        String firstname,
        String lastname,
        double rolling365Hours,
        double last90Hours,
        int sickDays365
) {
}
