package dk.trustworks.intranet.recruitmentservice.services;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * iCalendar (RFC 5545) writer for interview invitations — the manual-mode
 * fallback (interview scheduling plan Phase 6): when Graph writes are off
 * or an interview has no Outlook event, "Download .ics" turns manual
 * invitation sending into one click. Pure formatting, no Graph, no CDI —
 * DB-free testable.
 *
 * Times are written as {@code TZID=Europe/Copenhagen} wall clock with a
 * full VTIMEZONE definition, matching how the interview row stores them.
 * {@code METHOD:REQUEST} so mail clients treat the file as an invitation.
 *
 * Every text value passes {@link #escapeText}: backslash/semicolon/comma
 * escaping per RFC 5545, and CR/LF collapse to a literal {@code \n} —
 * a value can never break out of its property line (header-injection
 * safe). Long lines are folded at 74 octets.
 */
public final class InterviewIcsWriter {

    private static final DateTimeFormatter ICS_LOCAL =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private InterviewIcsWriter() {
    }

    public record IcsAttendee(String email, String name) {
    }

    public static String write(String uid,
                               LocalDateTime start,
                               int durationMinutes,
                               String summary,
                               String location,
                               String description,
                               String organizerEmail,
                               List<IcsAttendee> attendees,
                               LocalDateTime dtStampUtc) {
        StringBuilder ics = new StringBuilder(1024);
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "PRODID:-//Trustworks//Intranet//DA");
        line(ics, "VERSION:2.0");
        line(ics, "METHOD:REQUEST");
        line(ics, "BEGIN:VTIMEZONE");
        line(ics, "TZID:Europe/Copenhagen");
        line(ics, "BEGIN:STANDARD");
        line(ics, "DTSTART:19701025T030000");
        line(ics, "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU");
        line(ics, "TZOFFSETFROM:+0200");
        line(ics, "TZOFFSETTO:+0100");
        line(ics, "TZNAME:CET");
        line(ics, "END:STANDARD");
        line(ics, "BEGIN:DAYLIGHT");
        line(ics, "DTSTART:19700329T020000");
        line(ics, "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU");
        line(ics, "TZOFFSETFROM:+0100");
        line(ics, "TZOFFSETTO:+0200");
        line(ics, "TZNAME:CEST");
        line(ics, "END:DAYLIGHT");
        line(ics, "END:VTIMEZONE");
        line(ics, "BEGIN:VEVENT");
        line(ics, "UID:" + escapeText(uid));
        line(ics, "DTSTAMP:" + dtStampUtc.format(ICS_LOCAL) + "Z");
        line(ics, "DTSTART;TZID=Europe/Copenhagen:" + start.format(ICS_LOCAL));
        line(ics, "DTEND;TZID=Europe/Copenhagen:"
                + start.plusMinutes(durationMinutes).format(ICS_LOCAL));
        line(ics, "SUMMARY:" + escapeText(summary));
        if (location != null && !location.isBlank()) {
            line(ics, "LOCATION:" + escapeText(location));
        }
        if (description != null && !description.isBlank()) {
            line(ics, "DESCRIPTION:" + escapeText(description));
        }
        if (organizerEmail != null && !organizerEmail.isBlank()) {
            line(ics, "ORGANIZER;CN=Trustworks:mailto:" + escapeText(organizerEmail));
        }
        for (IcsAttendee attendee : attendees) {
            if (attendee.email() == null || attendee.email().isBlank()) {
                continue;
            }
            String cn = attendee.name() != null && !attendee.name().isBlank()
                    ? ";CN=" + escapeParam(attendee.name())
                    : "";
            line(ics, "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE"
                    + cn + ":mailto:" + escapeText(attendee.email()));
        }
        line(ics, "STATUS:CONFIRMED");
        line(ics, "SEQUENCE:0");
        line(ics, "END:VEVENT");
        line(ics, "END:VCALENDAR");
        return ics.toString();
    }

    /** RFC 5545 §3.3.11 TEXT escaping; CR/LF can never survive raw. */
    static String escapeText(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "\\n")
                .replace(";", "\\;")
                .replace(",", "\\,");
    }

    /** Param values (CN=) additionally must not carry quotes/colons. */
    static String escapeParam(String value) {
        return escapeText(value).replace("\"", "").replace(":", "");
    }

    /** Emit one content line, folded at 74 chars per RFC 5545 §3.1. */
    private static void line(StringBuilder ics, String content) {
        int index = 0;
        boolean first = true;
        while (index < content.length()) {
            int take = Math.min(first ? 74 : 73, content.length() - index);
            if (!first) {
                ics.append(' ');
            }
            ics.append(content, index, index + take).append("\r\n");
            index += take;
            first = false;
        }
        if (content.isEmpty()) {
            ics.append("\r\n");
        }
    }
}
