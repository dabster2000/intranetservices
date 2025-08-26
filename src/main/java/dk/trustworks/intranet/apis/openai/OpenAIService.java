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

    // Use a vision-capable model here if you're sending images (e.g., gpt-4o / gpt-4o-mini / gpt-5 models with vision).
    @ConfigProperty(name = "openai.model", defaultValue = "gpt-4o-mini")
    String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Legacy helper: JSON mode (json_object). Kept for backwards compatibility.
     * Not used by the updated expense flows, but safe to keep.
     */
    public String askQuestion(String question) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", model);
            req.put("max_output_tokens", 1024);

            // JSON mode in Responses: text.format (not response_format)
            ObjectNode text = req.putObject("text");
            ObjectNode format = text.putObject("format");
            format.put("type", "json_object");

            ArrayNode input = req.putArray("input");
            ObjectNode sys = input.addObject();
            sys.put("role", "system");
            // Mention "JSON" to satisfy json_object guardrail.
            sys.put("content", "Return ONLY valid JSON (json). No code fences. Single JSON object.");
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
            return extractOutputTextOrEmpty(json);

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed", e);
            return "{}";
        }
    }

    /**
     * Strict structured output via JSON Schema (Structured Outputs).
     * text.format.type = "json_schema" with strict=true.
     */
    public String askQuestionWithSchema(String system, String userMsg, ObjectNode jsonSchema, String schemaName) {
        return askQuestionWithSchema(system, userMsg, jsonSchema, schemaName, null);
    }

    /**
     * Overload that lets the caller specify a fallback JSON to return on model refusal.
     * Useful when you MUST return a schema-conformant JSON even if the model refuses.
     */
    public String askQuestionWithSchema(String system, String userMsg, ObjectNode jsonSchema, String schemaName, String refusalFallbackJson) {
        try {
            ObjectNode req = baseSchemaRequest(jsonSchema, schemaName);
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

            JsonNode root = objectMapper.readTree(payload);
            String refusal = extractRefusal(root);
            if (refusal != null) {
                log.warnf("[OpenAIService] Model refusal detected: %s", refusal);
                return refusalFallbackJson != null ? refusalFallbackJson : "{}";
            }
            return extractOutputTextOrEmpty(root);

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (schema)", e);
            return "{}";
        }
    }

    /**
     * Structured output + VISION: include base64 image (data URL) alongside text.
     * If the model refuses, returns the provided refusalFallbackJson (must match schema).
     *
     * NOTE: Requires a vision-capable model. The request uses content parts:
     * [{ type: "input_text", ... }, { type: "input_image", image_url: "data:<mime>;base64,<...>" }]
     * per the Responses API. :contentReference[oaicite:1]{index=1}
     */
    public String askWithSchemaAndImage(String system,
                                        String userInstructionText,
                                        String base64Image,
                                        String mimeType, // e.g., "image/jpeg", "image/png" (defaults if null)
                                        ObjectNode jsonSchema,
                                        String schemaName,
                                        String refusalFallbackJson) {
        try {
            ObjectNode req = baseSchemaRequest(jsonSchema, schemaName);

            ArrayNode input = req.putArray("input");
            if (system != null && !system.isBlank()) {
                ObjectNode sys = input.addObject();
                sys.put("role", "system");
                sys.put("content", system);
            }

            ObjectNode user = input.addObject();
            user.put("role", "user");

            // content array: input_text + input_image
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode textPart = content.addObject();
            textPart.put("type", "input_text");
            textPart.put("text", userInstructionText == null ? "" : userInstructionText);

            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "input_image");
            String dataUrl = toDataUrl(base64Image, mimeType);
            imagePart.put("image_url", dataUrl);

            user.set("content", content);

            String body = objectMapper.writeValueAsString(req);
            log.debugf("[OpenAIService] Sending response (json_schema + image). model=%s, bodySize=%d", model, body.length());

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return "{}";
            }

            JsonNode root = objectMapper.readTree(payload);
            String refusal = extractRefusal(root);
            if (refusal != null) {
                log.warnf("[OpenAIService] Model refusal detected: %s", refusal);
                return refusalFallbackJson != null ? refusalFallbackJson : "{}";
            }
            return extractOutputTextOrEmpty(root);

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (schema + image)", e);
            return "{}";
        }
    }

    /* ----------------- helpers ----------------- */

    private ObjectNode baseSchemaRequest(ObjectNode jsonSchema, String schemaName) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("model", model);
        req.put("max_output_tokens", 1024); // Responses API cap. :contentReference[oaicite:2]{index=2}

        ObjectNode text = req.putObject("text");
        ObjectNode format = text.putObject("format");
        format.put("type", "json_schema");
        ObjectNode schema = format.putObject("json_schema");
        schema.put("name", schemaName == null ? "schema" : schemaName);
        schema.put("strict", true);
        schema.set("schema", jsonSchema);
        return req;
    }

    private String toDataUrl(String base64, String mimeType) {
        String mt = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType.trim();
        if (base64 != null && base64.startsWith("data:")) return base64;
        return "data:" + mt + ";base64," + (base64 == null ? "" : base64.trim());
    }

    /**
     * Extracts textual output from Responses API object.
     * Prefers output[*].content[*].text, falls back to top-level output_text,
     * then (legacy) chat choices.
     */
    private String extractOutputTextOrEmpty(JsonNode root) {
        // top-level output_text convenience
        if (root.hasNonNull("output_text")) {
            String out = root.get("output_text").asText();
            return out == null || out.isBlank() ? "{}" : out.trim();
        }
        // output array
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
            String out = sb.toString().trim();
            return out.isEmpty() ? "{}" : out;
        }
        // last resort: chat shape
        String legacy = root.path("choices").path(0).path("message").path("content").asText(null);
        return legacy == null || legacy.isBlank() ? "{}" : legacy.trim();
    }

    /**
     * Extract an explicit 'refusal' string if present (Structured Outputs announcement).
     * If the API surfaces a refusal, we return that text so callers can convert it to a safe default. :contentReference[oaicite:3]{index=3}
     */
    private String extractRefusal(JsonNode root) {
        // Some Responses payloads include top-level refusal or per-output refusal.
        if (root.hasNonNull("refusal")) {
            return root.get("refusal").asText();
        }
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                if (item.hasNonNull("refusal")) return item.get("refusal").asText();
            }
        }
        // (Older Chat Completions had message.refusal – we don't need to cover it for Responses in production)
        return null;
    }
}
