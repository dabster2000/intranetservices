package dk.trustworks.intranet.recruitmentservice.airtable;

import dk.trustworks.intranet.recruitmentservice.airtable.AirtableClient.AirtableComment;
import dk.trustworks.intranet.recruitmentservice.airtable.AirtableClient.CommentAuthor;
import dk.trustworks.intranet.recruitmentservice.airtable.AirtableClient.CommentMention;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dk.trustworks.intranet.recruitmentservice.airtable.AirtableImportService.formatCommentNote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Airtable record-comment → timeline-note formatting (P21; the Alexander
 * Wichmann discussion thread finding, 2026-08-10): author + original
 * timestamp in the header (the event's occurred_at is import time), and
 * {@code @[usrXXX]} mention placeholders humanized via the comment's
 * mentioned map.
 */
class AirtableCommentNoteTest {

    @Test
    void header_carriesAuthorAndOriginalTimestamp() {
        AirtableComment comment = new AirtableComment("com1",
                new CommentAuthor("ditte.hjorth@trustworks.dk", "Ditte Hjorth"),
                "Alexander kommer til anden samtale den 11/8",
                "2026-07-02T18:51:00.000Z", null);
        String note = formatCommentNote(comment);
        assertTrue(note.startsWith("Airtable-kommentar fra Ditte Hjorth (2026-07-02 18:51 UTC):\n"));
        assertTrue(note.endsWith("Alexander kommer til anden samtale den 11/8"));
    }

    @Test
    void mentions_areHumanized() {
        AirtableComment comment = new AirtableComment("com2",
                new CommentAuthor("ditte.hjorth@trustworks.dk", "Ditte Hjorth"),
                "Nice.\n@[usrXcLvlnz6julpuy] vil du tage anden samtale sammen med mig?",
                "2026-07-01T18:57:40.000Z",
                Map.of("usrXcLvlnz6julpuy", new CommentMention("Jeppe Cramon")));
        String note = formatCommentNote(comment);
        assertTrue(note.contains("@Jeppe Cramon vil du tage anden samtale"));
    }

    @Test
    void missingAuthorAndTimestamp_degradeGracefully() {
        AirtableComment comment = new AirtableComment("com3", null, "Ja", null, null);
        assertEquals("Airtable-kommentar fra Ukendt:\nJa", formatCommentNote(comment));
    }
}
