package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P25 — the intent-parse prompt factory: strict schema shape (Structured
 * Outputs contract), the closed intent enum, the injection-containment
 * preamble, and a schema-conformant refusal fallback. The prompt is the
 * assistant's ONLY model surface, so its shape is contract-locked here.
 */
class AiAssistantPromptsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void schema_isStrictObjectWithAllPropertiesRequired() {
        ObjectNode schema = AiAssistantPrompts.schema();
        assertEquals("object", schema.get("type").asText());
        assertFalse(schema.get("additionalProperties").asBoolean());

        JsonNode properties = schema.get("properties");
        assertEquals(3, properties.size());
        List<String> required = new ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asText()));
        assertEquals(List.of("intent", "candidate_reference", "position_reference"),
                required, "Structured Outputs strict mode requires every property listed");
    }

    @Test
    void schema_locksTheClosedIntentEnum() {
        JsonNode intentEnum = AiAssistantPrompts.schema()
                .get("properties").get("intent").get("enum");
        List<String> values = new ArrayList<>();
        intentEnum.forEach(node -> values.add(node.asText()));
        assertEquals(List.of("CANDIDATE_STATUS", "POSITION_STATUS", "EVALUATIVE",
                "ACTION_REQUEST", "OTHER"), values);
    }

    @Test
    void schema_referencesAreNullableStrings() {
        JsonNode properties = AiAssistantPrompts.schema().get("properties");
        for (String field : List.of("candidate_reference", "position_reference")) {
            List<String> types = new ArrayList<>();
            properties.get(field).get("type").forEach(node -> types.add(node.asText()));
            assertEquals(List.of("string", "null"), types, field);
        }
    }

    @Test
    void systemPrompt_carriesTheContainmentPreambleAndDelimiters() {
        String system = AiAssistantPrompts.systemPrompt();
        assertTrue(system.contains(AiAssistantPrompts.DATA_START));
        assertTrue(system.contains(AiAssistantPrompts.DATA_END));
        assertTrue(system.contains("DATA"), "the data-not-instructions preamble (P9 idiom)");
        assertTrue(system.contains("ACTION_REQUEST"),
                "instruction-shaped content must route to the refused intent");
    }

    @Test
    void userPrompt_wrapsTheMentionInDelimiters_nullSafe() {
        String prompt = AiAssistantPrompts.userPrompt("where are we with Jens?");
        assertTrue(prompt.startsWith(AiAssistantPrompts.DATA_START));
        assertTrue(prompt.endsWith(AiAssistantPrompts.DATA_END));
        assertTrue(prompt.contains("where are we with Jens?"));
        assertTrue(AiAssistantPrompts.userPrompt(null)
                .contains(AiAssistantPrompts.DATA_START));
    }

    @Test
    void refusalFallback_isSchemaConformantOther() throws Exception {
        JsonNode fallback = MAPPER.readTree(AiAssistantPrompts.REFUSAL_FALLBACK_JSON);
        assertEquals("OTHER", fallback.get("intent").asText());
        assertTrue(fallback.get("candidate_reference").isNull());
        assertTrue(fallback.get("position_reference").isNull());
        assertNull(fallback.get("unexpected"));
    }
}
