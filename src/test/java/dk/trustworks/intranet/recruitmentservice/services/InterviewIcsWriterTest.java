package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manual-mode .ics fallback (interview scheduling plan Phase 6):
 * RFC 5545 structure, Copenhagen wall-clock times, and — the security
 * property — no attendee/location/summary value can break out of its
 * property line. Plain unit test in the DB-free tier that gates deploys.
 */
class InterviewIcsWriterTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 20, 10, 0);
    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 8, 13, 12, 0);

    private static String write(String summary, String location, String description) {
        return InterviewIcsWriter.write("int-1@trustworks.dk", START, 90,
                summary, location, description, "career@trustworks.dk",
                List.of(new InterviewIcsWriter.IcsAttendee("anna@example.com", "Anna Nielsen"),
                        new InterviewIcsWriter.IcsAttendee("bo@trustworks.dk", "Bo Berg")),
                STAMP);
    }

    @Test
    void writesARequestInvitation_withCopenhagenWallClockTimes() {
        String ics = write("Interview 1: Anna Nielsen", "HQ meeting room 2", "Vi ses!");

        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.contains("METHOD:REQUEST\r\n"));
        assertTrue(ics.contains("TZID:Europe/Copenhagen\r\n"), "full VTIMEZONE definition");
        assertTrue(ics.contains("DTSTART;TZID=Europe/Copenhagen:20260820T100000\r\n"));
        assertTrue(ics.contains("DTEND;TZID=Europe/Copenhagen:20260820T113000\r\n"),
                "end = start + duration");
        assertTrue(ics.contains("DTSTAMP:20260813T120000Z\r\n"));
        assertTrue(ics.contains("UID:int-1@trustworks.dk\r\n"));
        assertTrue(ics.contains("ORGANIZER;CN=Trustworks:mailto:career@trustworks.dk\r\n"));
        // Attendee lines exceed 74 chars and fold — unfold before matching.
        String unfolded = ics.replace("\r\n ", "");
        assertTrue(unfolded.contains(":mailto:anna@example.com"));
        assertTrue(unfolded.contains("CN=Bo Berg:mailto:bo@trustworks.dk"));
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
    }

    @Test
    void textValues_cannotBreakOutOfTheirPropertyLine() {
        // A hostile location tries to smuggle a raw property line in via
        // CRLF — it must arrive escaped, never as its own line.
        String ics = write("Interview; 1, x", "Room\r\nX-EVIL:injected", "Line1\nLine2");

        assertTrue(ics.contains("SUMMARY:Interview\\; 1\\, x\r\n"));
        assertTrue(ics.contains("LOCATION:Room\\nX-EVIL:injected\r\n"));
        assertFalse(ics.contains("\r\nX-EVIL"), "no injected raw line");
        assertTrue(ics.contains("DESCRIPTION:Line1\\nLine2\r\n"));
    }

    @Test
    void longLines_foldPerRfc5545() {
        String ics = write("A".repeat(200), null, null);

        for (String line : ics.split("\r\n")) {
            assertTrue(line.length() <= 74, "every physical line stays within 74 chars: " + line.length());
        }
        // Folded continuations start with a space and reassemble losslessly.
        assertTrue(ics.contains("\r\n A"));
        assertEquals(200, ics.replace("\r\n ", "")
                .lines().filter(l -> l.startsWith("SUMMARY:")).findFirst().orElseThrow()
                .substring("SUMMARY:".length()).length());
    }

    @Test
    void optionalPieces_omittedCleanly() {
        String ics = InterviewIcsWriter.write("uid", START, 60, "S", null, null, null,
                List.of(), STAMP);

        assertFalse(ics.contains("LOCATION"));
        assertFalse(ics.contains("DESCRIPTION"));
        assertFalse(ics.contains("ORGANIZER"));
        assertFalse(ics.contains("ATTENDEE"));
    }
}
