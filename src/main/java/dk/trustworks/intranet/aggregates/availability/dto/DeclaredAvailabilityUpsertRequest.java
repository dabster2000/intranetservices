package dk.trustworks.intranet.aggregates.availability.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One element of the {@code PUT /users/{useruuid}/declared-availability} body.
 *
 * <p>The subject is the path, never the body, and the source is decided server-side
 * (self → {@code SELF}, anyone else → {@code TEAMLEAD}), so neither can be forged.
 * Validation lives in {@code DeclaredAvailabilityValidator}: hours in [0, 24] in 0.25
 * steps, note ≤ 255 characters, no day twice in one batch.
 *
 * @param day   the day being declared
 * @param hours expected working hours that day; {@code 0} is a real statement
 *              ("looked at it, not working") and is stored as a row. {@code null} is a
 *              <em>clear</em>: the day's declaration is removed and the day is unplanned
 *              again, so one batch can carry additions, changes and removals together
 * @param note  optional free text, e.g. "eksamen"; ignored on a clear
 */
public record DeclaredAvailabilityUpsertRequest(LocalDate day, BigDecimal hours, String note) {
}
