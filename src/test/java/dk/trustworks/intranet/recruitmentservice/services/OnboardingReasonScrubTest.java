package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI gate now logs the model's reason so a wave of rejections can be
 * diagnosed from CloudWatch instead of guessed at. The reason is written by a
 * model that has just been shown a photograph of a sundhedskort, so it can
 * quote a CPR number — and these log groups never expire. These tests pin the
 * redaction that makes the line safe to keep.
 */
class OnboardingReasonScrubTest {

    @Test
    @DisplayName("CPR in the classic DDMMYY-XXXX shape never reaches the log")
    void scrub_redactsHyphenatedCpr() {
        String out = OnboardingDocumentValidationService.scrubReasonForLog(
                "The card shows CPR 010190-1234 but the name is blurred.");
        assertFalse(out.contains("010190-1234"));
        assertTrue(out.contains("[redacted]"));
        assertTrue(out.contains("the name is blurred"), "surrounding diagnosis is kept");
    }

    @Test
    @DisplayName("CPR without the hyphen is redacted too")
    void scrub_redactsUnhyphenatedCpr() {
        String out = OnboardingDocumentValidationService.scrubReasonForLog("CPR 0101901234 unreadable");
        assertFalse(out.contains("0101901234"));
    }

    @Test
    @DisplayName("Long card / licence numbers are redacted")
    void scrub_redactsLongDigitRuns() {
        String out = OnboardingDocumentValidationService.scrubReasonForLog("Licence no 98765432 is not legible");
        assertFalse(out.contains("98765432"));
    }

    @Test
    @DisplayName("ISO dates survive — 'expired 2024-03-11' is the diagnosis we want")
    void scrub_keepsIsoDates() {
        String out = OnboardingDocumentValidationService.scrubReasonForLog(
                "The licence expired on 2024-03-11.");
        assertEquals("The licence expired on 2024-03-11.", out);
    }

    @Test
    @DisplayName("Newlines are collapsed so a reason cannot forge a second log line")
    void scrub_collapsesWhitespace() {
        String out = OnboardingDocumentValidationService.scrubReasonForLog(
                "Blurry.\nINFO  [Onboarding] approved=true");
        assertFalse(out.contains("\n"));
        assertEquals("Blurry. INFO [Onboarding] approved=true", out);
    }

    @Test
    @DisplayName("Null and blank reasons scrub to empty, never to \"null\"")
    void scrub_handlesAbsentReason() {
        assertEquals("", OnboardingDocumentValidationService.scrubReasonForLog(null));
        assertEquals("", OnboardingDocumentValidationService.scrubReasonForLog("   "));
    }
}
