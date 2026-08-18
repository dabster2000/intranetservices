package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the infrastructure-failure split in
 * {@link OnboardingDocumentValidationService}: an upstream failure must be
 * reported as "we could not reach a verdict", never as "your kørekort was
 * rejected". Also covers the two things the strict-schema pin forced out of the
 * wire schema and into Java — the reason-length cap and the real "today" —
 * plus the refusal path, which does NOT use the {@code "{}"} sentinel.
 * Complements {@code OnboardingDocumentValidationSchemaTest}, which covers the
 * schema shape and the happy/guardrail parse paths.
 */
class OnboardingDocumentValidationFailureTest {

    @Test
    void sentinel_detectsOpenAiFailureMarkers() {
        // OpenAIService returns the literal "{}" for non-2xx, thrown
        // transport failures, and a 2xx that carried no output text.
        assertTrue(OnboardingDocumentValidationService.isServiceFailureSentinel("{}"));
        assertTrue(OnboardingDocumentValidationService.isServiceFailureSentinel("  {}  "));
        assertTrue(OnboardingDocumentValidationService.isServiceFailureSentinel(""));
        assertTrue(OnboardingDocumentValidationService.isServiceFailureSentinel("   "));
        assertTrue(OnboardingDocumentValidationService.isServiceFailureSentinel(null));
    }

    @Test
    void sentinel_doesNotSwallowRealVerdicts() {
        assertFalse(OnboardingDocumentValidationService.isServiceFailureSentinel(
                OnboardingDocumentValidationService.FALLBACK_REJECTED_JSON));
        assertFalse(OnboardingDocumentValidationService.isServiceFailureSentinel(
                "{\"approved\":false,\"reason\":\"Blurry photo\",\"checks\":{}}"));
        assertFalse(OnboardingDocumentValidationService.isServiceFailureSentinel("not json at all"));
    }

    @Test
    void parse_emptyObject_reportsServiceFailureNotDocumentRejection() {
        var d = OnboardingDocumentValidationService.parseDecision("{}");
        assertFalse(d.approved());
        assertEquals(OnboardingDocumentValidationService.SERVICE_UNAVAILABLE_REASON, d.reason());
    }

    @Test
    void parse_blankInput_reportsServiceFailure() {
        assertEquals(OnboardingDocumentValidationService.SERVICE_UNAVAILABLE_REASON,
                OnboardingDocumentValidationService.parseDecision("").reason());
        assertEquals(OnboardingDocumentValidationService.SERVICE_UNAVAILABLE_REASON,
                OnboardingDocumentValidationService.parseDecision(null).reason());
    }

    @Test
    void parse_genuineRejection_keepsTheModelsOwnReason() {
        String raw = """
            {
              "approved": false,
              "reason": "We could not read the expiry date — please upload a sharper photo.",
              "checks": {
                "isCorrectDocumentType": true,
                "isDanish": true,
                "isReadable": false,
                "isValid": false
              }
            }
            """;
        var d = OnboardingDocumentValidationService.parseDecision(raw);
        assertFalse(d.approved());
        assertTrue(d.reason().contains("expiry date"));
        assertFalse(d.reason().equals(OnboardingDocumentValidationService.SERVICE_UNAVAILABLE_REASON));
    }

    @Test
    void refusalFallback_isSchemaConformantAndReadsAsServiceFailure() {
        var d = OnboardingDocumentValidationService.parseDecision(
                OnboardingDocumentValidationService.FALLBACK_REJECTED_JSON);
        assertFalse(d.approved());
        assertEquals(OnboardingDocumentValidationService.SERVICE_UNAVAILABLE_REASON, d.reason());
    }

    // --- refusal path: OpenAIService returns our fallback JSON, not the sentinel ---

    @Test
    void refusalFallback_isDetectedAsItsOwnFailureMode() {
        assertTrue(OnboardingDocumentValidationService.isRefusalFallback(
                OnboardingDocumentValidationService.FALLBACK_REJECTED_JSON));
        // Whitespace-insensitive: the constant is a text block.
        assertTrue(OnboardingDocumentValidationService.isRefusalFallback(
                "  " + OnboardingDocumentValidationService.FALLBACK_REJECTED_JSON + "  "));
    }

    @Test
    void refusalFallback_doesNotMatchTheSentinelOrAGenuineRejection() {
        assertFalse(OnboardingDocumentValidationService.isRefusalFallback(null));
        assertFalse(OnboardingDocumentValidationService.isRefusalFallback("{}"));
        assertFalse(OnboardingDocumentValidationService.isRefusalFallback(
                "{\"approved\":false,\"reason\":\"Blurry photo\",\"checks\":{}}"));
    }

    // --- finding 1: length keywords must NOT reach the strict validator ---

    @Test
    void schema_reasonDeclaresNoLengthKeywords() {
        // minLength/maxLength are not in the strict structured-output subset we
        // have evidence for on the pinned model. A rejected keyword is an HTTP
        // 400 -> "{}" -> every onboarding upload blocked on a fail-closed gate.
        ObjectNode schema = OnboardingDocumentValidationService.buildSchema();
        JsonNode reason = schema.path("properties").path("reason");
        assertEquals("string", reason.path("type").asText());
        assertTrue(reason.path("minLength").isMissingNode());
        assertTrue(reason.path("maxLength").isMissingNode());
        // The expectation lives in the description and in the prompts instead.
        assertTrue(reason.path("description").asText().contains("240"));
    }

    @Test
    void prompts_stateTheReasonLengthExpectation() {
        for (OnboardingDocumentType type : OnboardingDocumentType.values()) {
            String p = OnboardingDocumentValidationService.systemPromptFor(type);
            assertTrue(p.contains("240"), "prompt for " + type + " must state the 240-char limit");
        }
    }

    @Test
    void capReason_clipsAnOverlongReasonToTheCap() {
        String longReason = "x".repeat(1000);
        String capped = OnboardingDocumentValidationService.capReason(longReason, false);
        assertTrue(capped.length() <= OnboardingDocumentValidationService.REASON_MAX_LENGTH,
                "capped length was " + capped.length());
    }

    @Test
    void capReason_substitutesAGenericSentenceWhenTooShortOrMissing() {
        assertTrue(OnboardingDocumentValidationService.capReason(null, false).length()
                >= OnboardingDocumentValidationService.REASON_MIN_LENGTH);
        assertTrue(OnboardingDocumentValidationService.capReason("   ", true).length()
                >= OnboardingDocumentValidationService.REASON_MIN_LENGTH);
        assertEquals("Document accepted.",
                OnboardingDocumentValidationService.capReason("ok", true));
    }

    @Test
    void capReason_leavesANormalReasonUntouched() {
        assertEquals("Image is too blurry to read the expiry date.",
                OnboardingDocumentValidationService.capReason(
                        "Image is too blurry to read the expiry date.", false));
    }

    @Test
    void parse_overlongReason_isCappedByTheParser() {
        String raw = "{\"approved\":false,\"reason\":\"" + "y".repeat(900) + "\","
                + "\"checks\":{\"isCorrectDocumentType\":false,\"isDanish\":false,"
                + "\"isReadable\":false,\"isValid\":false}}";
        var d = OnboardingDocumentValidationService.parseDecision(raw);
        assertFalse(d.approved());
        assertTrue(d.reason().length() <= OnboardingDocumentValidationService.REASON_MAX_LENGTH,
                "reason length was " + d.reason().length());
    }

    // --- finding 2: the model has no idea what "today" is unless we tell it ---

    @Test
    void userInstruction_carriesTheSuppliedDateInIsoForm() {
        String text = OnboardingDocumentValidationService.userInstructionFor(LocalDate.of(2026, 8, 18));
        assertTrue(text.contains("2026-08-18"), "instruction text was: " + text);
        assertTrue(text.toLowerCase().contains("today"));
        assertTrue(text.contains("schema"));
    }

    @Test
    void userInstruction_carriesTheRealCurrentDate() {
        LocalDate today = LocalDate.now();
        assertTrue(OnboardingDocumentValidationService.userInstructionFor(today)
                .contains(today.toString()));
    }

    @Test
    void dateSensitivePrompts_pointAtTheStatedDateNotTheModelsOwn() {
        // DRIVERS_LICENSE compares an expiry date and CRIMINAL_RECORD counts 3
        // months back — both must read "today" from the user message.
        String dl = OnboardingDocumentValidationService.systemPromptFor(
                OnboardingDocumentType.DRIVERS_LICENSE);
        String cr = OnboardingDocumentValidationService.systemPromptFor(
                OnboardingDocumentType.CRIMINAL_RECORD);
        assertTrue(dl.contains("TODAY'S DATE AS STATED IN THE USER MESSAGE"));
        assertTrue(cr.contains("TODAY'S DATE"));
        assertTrue(cr.contains("USER MESSAGE"));
    }
}
