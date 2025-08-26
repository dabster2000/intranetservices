package dk.trustworks.intranet.apis.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@JBossLog
@ApplicationScoped
public class OpenAIService {

    @Inject
    @RestClient
    OpenAIClient openAIClient;

    @ConfigProperty(name = "openai.api.key")
    String apiKey;

    // Keep configurable; set to gpt-5-nano in application.yaml if you have access.
    @ConfigProperty(name = "openai.model", defaultValue = "gpt-4o-mini")
    String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Single-prompt helper expecting JSON back.
     * Uses Responses API + JSON mode (text.format = json_object).
     * NOTE: No temperature is sent. Adds a system message that includes the word "JSON"
     * to satisfy the API requirement for json_object format.
     */
    public String askQuestion(String question) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", model);
            req.put("max_output_tokens", 1024); // Responses API cap (not max_tokens)

            // JSON mode in Responses: text.format (not response_format)
            ObjectNode text = req.putObject("text");
            ObjectNode format = text.putObject("format");
            format.put("type", "json_object");

            // Build the input (Responses uses `input`, not `messages`)
            ArrayNode input = req.putArray("input");

            // System message explicitly mentions JSON to satisfy the guardrail
            ObjectNode sys = input.addObject();
            sys.put("role", "system");
            sys.put("content", "Return ONLY valid JSON (json). No code fences. Output must be a single JSON object.");

            ObjectNode user = input.addObject();
            user.put("role", "user");
            user.put("content", question);

            String body = objectMapper.writeValueAsString(req);
            log.debugf("[OpenAIService] Sending response. model=%s, bodySize=%d", model, body.length());

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return "{}";
            }

            JsonNode json = objectMapper.readTree(payload);
            String out = extractOutputText(json);
            return out == null || out.isBlank() ? "{}" : out.trim();

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed", e);
            return "{}";
        }
    }

    /**
     * Strict structured output via JSON Schema (Structured Outputs).
     * text.format.type = "json_schema" with strict=true.
     * NOTE: No temperature is sent.
     */
    public String askQuestionWithSchema(String system, String userMsg, ObjectNode jsonSchema, String schemaName) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", model);
            req.put("max_output_tokens", 1024);

            ObjectNode text = req.putObject("text");
            ObjectNode format = text.putObject("format");
            format.put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema");
            schema.put("name", schemaName == null ? "schema" : schemaName);
            schema.put("strict", true);
            schema.set("schema", jsonSchema); // full JSON Schema here

            ArrayNode input = req.putArray("input");
            if (system != null && !system.isBlank()) {
                ObjectNode sys = input.addObject();
                sys.put("role", "system");
                sys.put("content", system);
            }
            ObjectNode user = input.addObject();
            user.put("role", "user");
            user.put("content", userMsg);

            String body = objectMapper.writeValueAsString(req);
            log.debugf("[OpenAIService] Sending response (json_schema). model=%s, bodySize=%d", model, body.length());

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return "{}";
            }

            JsonNode json = objectMapper.readTree(payload);
            String out = extractOutputText(json);
            return out == null || out.isBlank() ? "{}" : out.trim();

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (schema)", e);
            return "{}";
        }
    }

    /**
     * Parse Responses API output:
     * - Prefer `output[*].content[*].text` ("output_text" segments)
     * - Fallback to top-level `output_text`
     * - Fallback to old Chat Completions shape if present
     */
    private String extractOutputText(JsonNode root) {
        if (root.hasNonNull("output_text")) {
            return root.get("output_text").asText();
        }
        JsonNode output = root.path("output");
        if (output.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    for (JsonNode c : content) {
                        String type = c.path("type").asText();
                        if ("output_text".equals(type) || "text".equals(type)) {
                            String t = c.path("text").asText(null);
                            if (t != null) sb.append(t);
                        }
                    }
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        return root.path("choices").path(0).path("message").path("content").asText(null);
    }
}
