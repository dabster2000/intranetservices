package dk.trustworks.intranet.aggregates.availability.dto;

import java.time.LocalDate;

/**
 * Response of {@code GET /users/declared-availability/mode} — the cut-over state, read-only.
 *
 * <p>The frontend uses it to gate behaviour that must only change once declarations are
 * live: the Staffing presets that include juniors (spec §4.7.2) and the "live" hint on the
 * profile grid. Nothing here is a secret; it is exposed so the two sides cannot disagree
 * about which mode the resolver is in.
 */
public record DeclaredAvailabilityModeDTO(String mode, LocalDate floorDate) {
}
