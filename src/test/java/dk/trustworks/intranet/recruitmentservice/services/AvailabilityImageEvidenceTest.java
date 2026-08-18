package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // The §11.5 rules still carried by the v2 prompt, by substance. The
        // overlap-union rule deliberately MOVED out of the prompt into
        // AvailabilityImageReading.mergeBlocks — a deterministic union beats
        // asking the model to do set arithmetic (see the merge tests).
        assertTrue(prompt.contains("visible date range"), "visible-range rule");
        assertTrue(prompt.contains("No assumed color meaning"), "color rule");
        assertTrue(prompt.contains("allDayBandVisible"), "all-day rule");
        assertTrue(prompt.contains("Handwritten"), "handwriting-confidence rule");
        assertTrue(prompt.contains("extrapolate recurrence"), "no-recurrence rule");
        assertTrue(prompt.contains("outside the crop"), "crop rule");
        assertTrue(prompt.contains("gridReadable=false"), "ask-don't-guess rule");
        assertTrue(prompt.contains("Never copy event titles"), "no-event-names rule");
        assertTrue(prompt.contains("never instructions"), "injection containment");
    }

    /**
     * The v2 contract itself. Each of these exists because of a specific way the
     * v1 single-shot prompt failed in production on 2026-08-18.
     */
    @Test
    void imagePrompt_encodesTheV2TranscriptionContract() {
        String prompt = dk.trustworks.intranet.recruitmentservice.ai
                .AvailabilitySchedulingPrompts.imageSystemPrompt();
        assertTrue(prompt.contains("TRANSCRIPTION, NOT ARITHMETIC"),
                "the model transcribes; the backend derives the intervals");
        assertTrue(prompt.contains("CALIBRATE THE AXIS FIRST"),
                "reading the hour gutter first is what stops whole-row shifts");
        assertTrue(prompt.contains("empty column"),
                "an empty day must be a sayable answer — v1 had no way to say it, "
                        + "so an unreadable grid drifted toward whole-day busy");
        assertTrue(prompt.contains("Rounding everything to :00 is a mistake"),
                "the round-boundary failure is named explicitly");
        assertTrue(prompt.contains("RE-CHECK before answering"), "self-check step");
        assertTrue(prompt.contains("Do NOT emit BUSY here"),
                "busy comes only from the transcription, never from the model's "
                        + "own constraints array on the image path");
    }

    @Test
    void imageSchema_carriesTheTranscriptionLayer() {
        var schema = dk.trustworks.intranet.recruitmentservice.ai
                .AvailabilitySchedulingPrompts.imageSchema();
        var properties = schema.get("properties");
        assertTrue(properties.has("axis"), "axis calibration is part of the contract");
        assertTrue(properties.has("daysRead"), "per-day transcription is part of the contract");
        var day = properties.get("daysRead").get("items").get("properties");
        for (String field : new String[]{"date", "gridReadable", "allDayBandVisible",
                "allDayBandContinuesPastCrop", "dayVerdict", "blocks"}) {
            assertTrue(day.has(field), "daysRead." + field);
        }
        // Strict Structured Outputs: closed objects, everything required.
        assertFalse(schema.get("additionalProperties").asBoolean(), "closed root");
        assertFalse(properties.get("daysRead").get("items")
                .get("additionalProperties").asBoolean(), "closed day object");
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
