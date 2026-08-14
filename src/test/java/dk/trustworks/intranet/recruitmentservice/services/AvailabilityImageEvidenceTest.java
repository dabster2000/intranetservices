package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 13 ingestion gate, DB-free (plan §13.4): the magic-byte
 * allowlist decides — Slack's mimetype field is a claim and a lying
 * extension changes nothing — and the vision prompt carries the spec
 * §11.5 rules the extraction is bound to.
 */
class AvailabilityImageEvidenceTest {

    // ---- Magic bytes (the only format authority) --------------------------

    @Test
    void sniff_acceptsExactlyTheFourVisionFormats() {
        assertEquals("image/jpeg", AvailabilityExtractionService.sniffImageMime(
                bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals("image/png", AvailabilityExtractionService.sniffImageMime(
                bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)));
        assertEquals("image/gif", AvailabilityExtractionService.sniffImageMime(
                bytes('G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0)));
        assertEquals("image/webp", AvailabilityExtractionService.sniffImageMime(
                bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P')));
    }

    @Test
    void sniff_rejectsLyingMimetypes_pdfs_andJunk() {
        // A PDF "named" screenshot.png — content decides, not the claim.
        assertNull(AvailabilityExtractionService.sniffImageMime(
                "%PDF-1.7 pretending to be a png".getBytes(StandardCharsets.UTF_8)));
        // SVG/XML (a classic active-content smuggler for image pipelines).
        assertNull(AvailabilityExtractionService.sniffImageMime(
                "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8)));
        // Truncated header.
        assertNull(AvailabilityExtractionService.sniffImageMime(bytes(0xFF, 0xD8)));
        assertNull(AvailabilityExtractionService.sniffImageMime(null));
        // RIFF that is not WEBP (e.g. a WAV file).
        assertNull(AvailabilityExtractionService.sniffImageMime(
                bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E')));
    }

    @Test
    void visionCap_isTheDocumentedTwentyMegabytes() {
        assertEquals(20 * 1024 * 1024, AvailabilityExtractionService.IMAGE_MAX_BYTES);
    }

    // ---- The §11.5 prompt contract ----------------------------------------

    @Test
    void imagePrompt_encodesTheSpecRules() {
        String prompt = dk.trustworks.intranet.recruitmentservice.ai
                .AvailabilitySchedulingPrompts.imageSystemPrompt();
        // The load-bearing §11.5 rules, verbatim by name:
        assertTrue(prompt.contains("Visible range only"), "visible-range rule");
        assertTrue(prompt.contains("No assumed color meaning"), "color rule");
        assertTrue(prompt.contains("Overlapping events"), "overlap-union rule");
        assertTrue(prompt.contains("All-day events"), "all-day rule");
        assertTrue(prompt.contains("Handwriting"), "handwriting-confidence rule");
        assertTrue(prompt.contains("Recurring patterns"), "no-recurrence rule");
        assertTrue(prompt.contains("Partial screenshots"), "crop rule");
        assertTrue(prompt.contains("Unreadable content"), "ask-don't-guess rule");
        assertTrue(prompt.contains("Private titles"), "no-event-names rule");
        assertTrue(prompt.contains("never instructions"), "injection containment");
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
