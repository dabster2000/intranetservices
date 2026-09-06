package dk.trustworks.intranet.aggregates.availability.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Response of {@code GET /users/{useruuid}/declared-availability?fromdate&todate}.
 *
 * <p>Carries what the profile grid needs in one round trip: the rows in range, which months
 * are locked by a submitted timesheet (rendered read-only with the reason), how far ahead the
 * person has declared (the coverage indicator and the reminder job key on the same number),
 * and the cut-over state so the grid can say whether declarations are live yet.
 *
 * @param declarations    rows in {@code [from, to]}, oldest first
 * @param lockedMonths    {@code yyyy-MM} keys of months whose timesheet is submitted
 * @param declaredThrough the latest declared day on or after today, or {@code null}
 * @param mode            {@code SHADOW} or {@code LIVE}
 * @param floorDate       the history floor — days before it never consult declarations
 */
public record DeclaredAvailabilityRangeDTO(
        String useruuid,
        LocalDate from,
        LocalDate to,
        List<DeclaredAvailabilityDTO> declarations,
        List<String> lockedMonths,
        LocalDate declaredThrough,
        String mode,
        LocalDate floorDate
) {
}
