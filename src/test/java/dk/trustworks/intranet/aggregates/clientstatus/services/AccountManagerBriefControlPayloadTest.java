package dk.trustworks.intranet.aggregates.clientstatus.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.clientstatus.services.AccountManagerBriefService.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests (no Quarkus, no DB) for how the controlling approval flag and note are rendered
 * into the AM-brief payload, and for the note-context sanitizer.
 */
class AccountManagerBriefControlPayloadTest {

    @Test
    void buildPayload_rendersApprovedAndNoteWhenPresent() {
        var approvedMonth = new MonthAnalysis("januar 2026", 500_000, 500_000, 0, false,
                List.of(), List.of(), 0, true, "Faktureres kvartalsvis efter aftale");
        var client = new ClientAnalysis("Banedanmark", List.of(approvedMonth), 0, 0);

        ObjectNode payload = AccountManagerBriefService.buildPayload(
                "Tommy", Framing.TO_AM, List.of(client), List.of(), 1, 0, 12, "januar 2026", false);

        JsonNode m = payload.get("clients").get(0).get("months").get(0);
        assertTrue(m.get("approved").asBoolean(), "approved month must carry approved=true");
        assertEquals("Faktureres kvartalsvis efter aftale", m.get("note").asText());
    }

    @Test
    void buildPayload_omitsApprovedAndNoteWhenAbsent() {
        var plainMonth = new MonthAnalysis("februar 2026", 500_000, 100_000, -400_000, true,
                List.of(), List.of(), 0, false, null);
        var client = new ClientAnalysis("Banedanmark", List.of(plainMonth), 1, 400_000);

        ObjectNode payload = AccountManagerBriefService.buildPayload(
                "Tommy", Framing.TO_AM, List.of(client), List.of(), 1, 0, 12, "februar 2026", false);

        JsonNode m = payload.get("clients").get(0).get("months").get(0);
        assertNull(m.get("approved"), "non-approved month must not emit an approved key");
        assertNull(m.get("note"), "month without a note must not emit a note key");
        assertTrue(m.get("gap").asBoolean());
    }

    @Test
    void buildPayload_noteOnUnapprovedMonthStillRendered() {
        // A note may exist without an approval; it rides along as context regardless of gap state.
        var gapWithNote = new MonthAnalysis("marts 2026", 500_000, 100_000, -400_000, true,
                List.of(), List.of(), 0, false, "Kunden holder faktura tilbage til projektafslutning");
        var client = new ClientAnalysis("Energinet", List.of(gapWithNote), 1, 400_000);

        ObjectNode payload = AccountManagerBriefService.buildPayload(
                "Tommy", Framing.TO_AM, List.of(client), List.of(), 1, 0, 12, "marts 2026", false);

        JsonNode m = payload.get("clients").get(0).get("months").get(0);
        assertNull(m.get("approved"));
        assertEquals("Kunden holder faktura tilbage til projektafslutning", m.get("note").asText());
        assertTrue(m.get("gap").asBoolean(), "an un-approved gap month keeps gap=true even with a note");
    }

    @Test
    void noteContext_stripsHtmlAndControlCharsAndCapsAt500() {
        assertNull(AccountManagerBriefService.noteContext(null));
        assertNull(AccountManagerBriefService.noteContext("   "));
        assertEquals("Banedanmark", AccountManagerBriefService.noteContext("<b>Banedanmark</b>"));
        assertEquals("a b", AccountManagerBriefService.noteContext("a\nb"));
        // Kept up to 500 chars (unlike the 120-char name sanitizer), truncated beyond.
        String longNote = "x".repeat(700);
        assertEquals(500, AccountManagerBriefService.noteContext(longNote).length());
        assertEquals(300, AccountManagerBriefService.noteContext("x".repeat(300)).length());
    }
}
