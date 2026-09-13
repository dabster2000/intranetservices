package dk.trustworks.intranet.aggregates.crm.calendar.dto;

/**
 * The toggle. Boxed rather than primitive so a body that forgot the field is a 400 instead
 * of silently reading as "off" — revoking somebody's consent because a payload was
 * malformed would be the worst possible failure mode here.
 */
public record CalendarConsentRequest(Boolean enabled) {
}
