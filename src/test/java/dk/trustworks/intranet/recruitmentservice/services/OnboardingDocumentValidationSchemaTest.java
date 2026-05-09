package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the package-private statics in
 * {@link OnboardingDocumentValidationService}. Mockito cannot mock the
 * injected {@code OpenAIService}'s static-Panache dependencies and the
 * local dev env lacks the OpenAI key — so we test only the deterministic
 * helpers here. End-to-end behaviour is verified by manual staging smoke.
 */
class OnboardingDocumentValidationSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void schema_hasExpectedTopLevelShape() {
        ObjectNode schema = OnboardingDocumentValidationService.buildSchema();
        assertEquals("object", schema.path("type").asText());
        assertEquals(false, schema.path("additionalProperties").asBoolean(true));
        JsonNode required = schema.path("required");
        assertTrue(required.isArray());
        assertEquals(3, required.size());
    }

    @Test
    void schema_includesAllFourBooleanChecks() {
        ObjectNode schema = OnboardingDocumentValidationService.buildSchema();
        JsonNode checks = schema.path("properties").path("checks").path("properties");
        assertEquals("boolean", checks.path("isCorrectDocumentType").path("type").asText());
        assertEquals("boolean", checks.path("isDanish").path("type").asText());
        assertEquals("boolean", checks.path("isReadable").path("type").asText());
        assertEquals("boolean", checks.path("isValid").path("type").asText());
    }

    @Test
    void schema_reasonHasLengthBounds() {
        ObjectNode schema = OnboardingDocumentValidationService.buildSchema();
        JsonNode reason = schema.path("properties").path("reason");
        assertEquals("string", reason.path("type").asText());
        assertEquals(5, reason.path("minLength").asInt());
        assertEquals(240, reason.path("maxLength").asInt());
    }

    @Test
    void fallbackJson_isSchemaConformantAndRejected() throws Exception {
        JsonNode fallback = MAPPER.readTree(
                OnboardingDocumentValidationService.FALLBACK_REJECTED_JSON);
        assertEquals(false, fallback.path("approved").asBoolean(true));
        assertTrue(fallback.path("reason").asText().length() >= 5);
        JsonNode checks = fallback.path("checks");
        assertEquals(false, checks.path("isCorrectDocumentType").asBoolean(true));
        assertEquals(false, checks.path("isDanish").asBoolean(true));
        assertEquals(false, checks.path("isReadable").asBoolean(true));
        assertEquals(false, checks.path("isValid").asBoolean(true));
    }

    @Test
    void prompt_driversLicense_mentionsKorekort() {
        String p = OnboardingDocumentValidationService.systemPromptFor(
                OnboardingDocumentType.DRIVERS_LICENSE);
        assertTrue(p.toLowerCase().contains("kørekort") || p.toLowerCase().contains("korekort")
                || p.toLowerCase().contains("driver"));
        assertTrue(p.contains("isCorrectDocumentType"));
        assertTrue(p.contains("isDanish"));
        assertTrue(p.contains("isReadable"));
        assertTrue(p.contains("isValid"));
    }

    @Test
    void prompt_healthInsurance_mentionsSundhedskort() {
        String p = OnboardingDocumentValidationService.systemPromptFor(
                OnboardingDocumentType.HEALTH_INSURANCE);
        assertTrue(p.toLowerCase().contains("sundhedskort"));
        assertTrue(p.toLowerCase().contains("ehic") || p.toLowerCase().contains("blue"));
    }

    @Test
    void prompt_criminalRecord_mentionsStraffeattest() {
        String p = OnboardingDocumentValidationService.systemPromptFor(
                OnboardingDocumentType.CRIMINAL_RECORD);
        assertTrue(p.toLowerCase().contains("straffeattest"));
        assertTrue(p.toLowerCase().contains("3 month") || p.contains("3 calendar months"));
    }

    @Test
    void prompt_allTypesAreDistinct() {
        String dl = OnboardingDocumentValidationService.systemPromptFor(
                OnboardingDocumentType.DRIVERS_LICENSE);
        String hi = OnboardingDocumentValidationService.systemPromptFor(
                OnboardingDocumentType.HEALTH_INSURANCE);
        String cr = OnboardingDocumentValidationService.systemPromptFor(
                OnboardingDocumentType.CRIMINAL_RECORD);
        assertTrue(!dl.equals(hi) && !hi.equals(cr) && !dl.equals(cr));
    }

    @Test
    void parse_validApprovedResponse() {
        String raw = """
            {
              "approved": true,
              "reason": "Document looks good.",
              "checks": {
                "isCorrectDocumentType": true,
                "isDanish": true,
                "isReadable": true,
                "isValid": true
              }
            }
            """;
        var d = OnboardingDocumentValidationService.parseDecision(raw);
        assertTrue(d.approved());
        assertEquals("Document looks good.", d.reason());
    }

    @Test
    void parse_validRejectedResponse() {
        String raw = """
            {
              "approved": false,
              "reason": "Image is too blurry to read the expiry date.",
              "checks": {
                "isCorrectDocumentType": true,
                "isDanish": true,
                "isReadable": false,
                "isValid": true
              }
            }
            """;
        var d = OnboardingDocumentValidationService.parseDecision(raw);
        assertEquals(false, d.approved());
        assertEquals("Image is too blurry to read the expiry date.", d.reason());
    }

    @Test
    void parse_guardrail_flipsApprovedToFalseWhenCheckFails() {
        // Model claims approved=true but isReadable=false. Guardrail forces rejection.
        String raw = """
            {
              "approved": true,
              "reason": "Looks fine.",
              "checks": {
                "isCorrectDocumentType": true,
                "isDanish": true,
                "isReadable": false,
                "isValid": true
              }
            }
            """;
        var d = OnboardingDocumentValidationService.parseDecision(raw);
        assertEquals(false, d.approved());
        assertTrue(d.reason().toLowerCase().contains("inconsistency")
                || d.reason().toLowerCase().contains("re-upload"));
    }

    @Test
    void parse_emptyOrGarbage_returnsRejected() {
        var empty = OnboardingDocumentValidationService.parseDecision("");
        assertEquals(false, empty.approved());
        var garbage = OnboardingDocumentValidationService.parseDecision("not json at all");
        assertEquals(false, garbage.approved());
    }

    @Test
    void parse_missingChecks_returnsRejected() {
        String raw = """
            { "approved": true, "reason": "All good" }
            """;
        var d = OnboardingDocumentValidationService.parseDecision(raw);
        assertEquals(false, d.approved());
    }

    @Test
    void parse_reasonMissing_usesGenericFallback() {
        String raw = """
            {
              "approved": false,
              "checks": {
                "isCorrectDocumentType": false,
                "isDanish": false,
                "isReadable": false,
                "isValid": false
              }
            }
            """;
        var d = OnboardingDocumentValidationService.parseDecision(raw);
        assertEquals(false, d.approved());
        assertTrue(d.reason().length() >= 5);
    }

    @Test
    void validate_publicMethodExistsWithExpectedSignature() throws Exception {
        java.lang.reflect.Method m = OnboardingDocumentValidationService.class
                .getMethod("validate", OnboardingDocumentType.class, byte[].class, String.class);
        assertEquals(
                OnboardingDocumentValidationService.ValidationDecision.class,
                m.getReturnType());
    }
}
