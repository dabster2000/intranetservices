package dk.trustworks.intranet.aggregates.availability.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code POST /users/{useruuid}/declared-availability/copy} — the "kopiér"
 * function from the junior team's spec §3.
 *
 * <p>Every target week is overwritten to mirror the source week: source rows are copied
 * day-for-day with {@code source = SYSTEM}, and target days the source does not declare
 * are removed. All targets succeed or none do.
 *
 * @param sourceWeekStart  a Monday
 * @param targetWeekStarts Mondays, none equal to the source
 */
public record DeclaredAvailabilityCopyRequest(LocalDate sourceWeekStart, List<LocalDate> targetWeekStarts) {
}
