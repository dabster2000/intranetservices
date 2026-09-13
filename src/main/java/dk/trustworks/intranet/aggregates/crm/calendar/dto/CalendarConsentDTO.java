package dk.trustworks.intranet.aggregates.crm.calendar.dto;

import java.time.LocalDateTime;

/**
 * One employee's calendar-metadata consent, and enough context for the profile page to
 * explain itself.
 *
 * @param enabled      what actually applies right now
 * @param explicit     true when the person has decided; false when {@link #enabled} is
 *                     only the role default and nobody has been asked
 * @param roleDefault  what their role would give them, so the UI can say "on because you
 *                     are in sales" rather than leaving a toggle mysteriously pre-set
 * @param decidedAt    when they decided, or null if they never have
 */
public record CalendarConsentDTO(
        String userUuid,
        boolean enabled,
        boolean explicit,
        boolean roleDefault,
        LocalDateTime decidedAt) {
}
