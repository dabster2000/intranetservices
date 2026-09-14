package dk.trustworks.intranet.aggregates.crm.calendar.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * One "Seen in calendars" row (spec §2.5): a company several colleagues keep meeting that
 * Intra has never heard of.
 *
 * <p>Matches {@code ICalendarSuggestion} in {@code src/lib/crm/accountTypes.ts} field for
 * field.
 *
 * <p>{@code people} is the Trustworks colleagues whose calendars the domain appeared in —
 * the mailbox owners, never the attendees on the other side. That is what makes the row
 * actionable ("Tommy and Lukas keep meeting them") without storing anything about the
 * people at the company.
 *
 * @param domain      lower-cased; a company, never a person
 * @param meetings90d meetings in the last 90 days
 * @param meetingsTotal meetings ever seen, within the 12-month retention window
 * @param firstSeen   the earliest meeting still held
 * @param lastSeen    the most recent
 * @param people      Trustworks colleagues who met them, most recent first, capped
 * @param peopleTotal how many colleagues in total, before the cap
 */
public record CalendarSuggestionDTO(
        String domain,
        int meetings90d,
        int meetingsTotal,
        LocalDate firstSeen,
        LocalDate lastSeen,
        List<PersonDTO> people,
        int peopleTotal) {
}
