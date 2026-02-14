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
    @ConfigProperty(name = "openai.model", defaultValue = "gpt-5-nano")
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
            req.put("max_output_tokens", 16384);

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
     * Structured output + WEB SEARCH: JSON schema validation with internet search capability.
     * Combines strict schema enforcement with web search for location-based validation.
     *
     * NOTE: This is experimental - GPT-5 models may have limitations when combining
     * json_schema format with web_search tools. If HTTP 400 occurs, fall back to
     * plain text responses with manual JSON parsing.
     *
     * @param system System prompt (role: system)
     * @param userMsg User message (role: user)
     * @param jsonSchema JSON schema for structured output
     * @param schemaName Schema name identifier
     * @param refusalFallbackJson JSON to return if model refuses (must match schema)
     * @param userCountry ISO country code for location-aware web search (e.g., "DK" for Denmark)
     * @return Structured JSON response matching schema, or refusalFallbackJson on error
     */
    public String askQuestionWithSchemaAndWebSearch(String system,
                                                    String userMsg,
                                                    ObjectNode jsonSchema,
                                                    String schemaName,
                                                    String refusalFallbackJson,
                                                    String userCountry) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", model);
            req.put("max_output_tokens", 4096);

            // Enable web search tool
            ArrayNode tools = req.putArray("tools");
            ObjectNode webSearch = tools.addObject();
            webSearch.put("type", "web_search");
            ObjectNode userLocation = webSearch.putObject("user_location");
            userLocation.put("type", "approximate");
            userLocation.put("country", userCountry != null ? userCountry : "DK");
            webSearch.put("search_context_size", "medium");

            // Enable reasoning for web search
            ObjectNode reasoning = req.putObject("reasoning");
            reasoning.put("effort", "medium");

            // Configure text format with JSON schema (flat structure, not nested)
            ObjectNode text = req.putObject("text");
            ObjectNode format = text.putObject("format");
            format.put("type", "json_schema");
            format.put("name", schemaName == null ? "schema" : schemaName);
            format.set("schema", jsonSchema);
            format.put("strict", true);

            // Store response and include reasoning
            req.put("store", true);
            ArrayNode include = req.putArray("include");
            include.add("reasoning.encrypted_content");
            include.add("web_search_call.action.sources");

            // Build input array
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
            log.debugf("[OpenAIService] Sending response (json_schema + web_search). model=%s, country=%s, bodySize=%d",
                    model, userCountry, body.length());

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return refusalFallbackJson != null ? refusalFallbackJson : "{}";
            }

            JsonNode root = objectMapper.readTree(payload);
            String refusal = extractRefusal(root);
            if (refusal != null) {
                log.warnf("[OpenAIService] Model refusal detected: %s", refusal);
                return refusalFallbackJson != null ? refusalFallbackJson : "{}";
            }
            return extractOutputTextOrEmpty(root);

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (schema + web search)", e);
            return refusalFallbackJson != null ? refusalFallbackJson : "{}";
        }
    }

    /**
     * Structured output + WEB SEARCH (NO IMAGE): JSON schema validation with internet search capability.
     * Combines strict schema enforcement with web search for text-based validation (no vision).
     *
     * Use this when you need structured output + web search but don't have an image to analyze.
     * This is more efficient than image-based methods when working with text descriptions.
     *
     * @param system System prompt (role: system)
     * @param userMsg User message (role: user)
     * @param jsonSchema JSON schema for structured output
     * @param schemaName Schema name identifier
     * @param refusalFallbackJson JSON to return if model refuses (must match schema)
     * @param userCountry ISO country code for location-aware web search (e.g., "DK" for Denmark)
     * @return Structured JSON response matching schema, or refusalFallbackJson on error
     */
    public String askWithSchemaAndWebSearch(String system,
                                           String userMsg,
                                           ObjectNode jsonSchema,
                                           String schemaName,
                                           String refusalFallbackJson,
                                           String userCountry) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", model);
            req.put("max_output_tokens", 32768);

            // Enable web search tool
            ArrayNode tools = req.putArray("tools");
            ObjectNode webSearch = tools.addObject();
            webSearch.put("type", "web_search");
            ObjectNode userLocation = webSearch.putObject("user_location");
            userLocation.put("type", "approximate");
            userLocation.put("country", userCountry != null ? userCountry : "DK");
            webSearch.put("search_context_size", "medium");

            // Enable reasoning for web search
            ObjectNode reasoning = req.putObject("reasoning");
            reasoning.put("effort", "medium");

            // Configure text format with JSON schema (flat structure, not nested)
            ObjectNode text = req.putObject("text");
            ObjectNode format = text.putObject("format");
            format.put("type", "json_schema");
            format.put("name", schemaName == null ? "schema" : schemaName);
            format.set("schema", jsonSchema);
            format.put("strict", true);

            // Store response and include reasoning
            req.put("store", true);
            ArrayNode include = req.putArray("include");
            include.add("reasoning.encrypted_content");
            include.add("web_search_call.action.sources");

            // Build input array
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
            log.debugf("[OpenAIService] Sending response (json_schema + web_search, no image). model=%s, country=%s, bodySize=%d",
                    model, userCountry, body.length());

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return refusalFallbackJson != null ? refusalFallbackJson : "{}";
            }

            JsonNode root = objectMapper.readTree(payload);
            String refusal = extractRefusal(root);
            if (refusal != null) {
                log.warnf("[OpenAIService] Model refusal detected: %s", refusal);
                return refusalFallbackJson != null ? refusalFallbackJson : "{}";
            }
            return extractOutputTextOrEmpty(root);

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (schema + web search, no image)", e);
            return refusalFallbackJson != null ? refusalFallbackJson : "{}";
        }
    }

    /**
     * Plain text response + WEB SEARCH: Returns JSON as plain text (no json_schema enforcement).
     * Works with GPT-5 models that don't support json_schema with tools.
     * Caller must parse and validate JSON manually.
     *
     * This method uses plain text format instead of json_schema because GPT-5 models
     * do not support combining json_schema with tools (web_search) and reasoning.
     *
     * @param system System prompt describing expected JSON schema
     * @param userMsg User message (validation context)
     * @param userCountry ISO country code for location-aware web search (e.g., "DK")
     * @return Plain text JSON response (must be parsed manually by caller)
     */
    public String askQuestionWithWebSearchPlainText(String system,
                                                    String userMsg,
                                                    String userCountry) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", model);
            req.put("max_output_tokens", 4096);

            // Enable web search tool
            ArrayNode tools = req.putArray("tools");
            ObjectNode webSearch = tools.addObject();
            webSearch.put("type", "web_search");
            ObjectNode userLocation = webSearch.putObject("user_location");
            userLocation.put("type", "approximate");
            userLocation.put("country", userCountry != null ? userCountry : "DK");
            webSearch.put("search_context_size", "medium");

            // Enable reasoning for web search
            ObjectNode reasoning = req.putObject("reasoning");
            reasoning.put("effort", "medium");

            // Plain text format (NOT json_schema) - required for GPT-5 + tools compatibility
            ObjectNode text = req.putObject("text");
            ObjectNode format = text.putObject("format");
            format.put("type", "text");
            text.put("verbosity", "medium");

            // Store response and include reasoning
            req.put("store", true);
            ArrayNode include = req.putArray("include");
            include.add("reasoning.encrypted_content");
            include.add("web_search_call.action.sources");

            // Build input array
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
            log.debugf("[OpenAIService] Sending response (plain text + web_search). model=%s, country=%s, bodySize=%d",
                    model, userCountry, body.length());

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return "{}";
            }

            JsonNode root = objectMapper.readTree(payload);
            String result = extractOutputTextOrEmpty(root);

            // Return raw JSON text (caller must parse and validate)
            return result;

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (plain text + web search)", e);
            return "{}";
        }
    }

    /**
     * Simple text response + VISION: include base64 image (data URL) alongside text.
     * Returns plain text response without structured JSON schema.
     *
     * NOTE: Requires a vision-capable model (e.g., gpt-5-nano, gpt-4o).
     * The request uses content parts:
     * [{ type: "input_text", ... }, { type: "input_image", image_url: "data:<mime>;base64,<...>" }]
     */
    public String askSimpleQuestionWithImage(String system,
                                             String userInstructionText,
                                             String base64Image,
                                             String mimeType) { // e.g., "image/jpeg", "image/png"
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", model);
            req.put("max_output_tokens", 32768);

            // No format constraints - just plain text response
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
            log.debugf("[OpenAIService] Sending response (simple text + image). model=%s, bodySize=%d", model, body.length());

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return "Validation error: OpenAI API returned status " + http.getStatus();
            }

            JsonNode root = objectMapper.readTree(payload);
            String result = extractOutputTextOrEmpty(root);

            // If result is empty or just "{}", return a fallback message
            if (result == null || result.isBlank() || result.equals("{}")) {
                return "Validation error: Unable to process image";
            }

            return result;

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (simple text + image)", e);
            return "Validation error: " + e.getMessage();
        }
    }

    /**
     * Simple text response + VISION + WEB SEARCH: include base64 image alongside text with internet search capability.
     * Returns plain text response without structured JSON schema.
     * Enables GPT-5 web search to look up real-time information (e.g., store locations, distances).
     *
     * NOTE: Requires a vision-capable model with web search (e.g., gpt-5-nano, gpt-5).
     * The request uses content parts:
     * [{ type: "input_text", ... }, { type: "input_image", image_url: "data:<mime>;base64,<...>" }]
     * plus tools configuration for web_search.
     *
     * @param system System prompt (role: system)
     * @param userInstructionText User instructions (role: user, type: input_text)
     * @param base64Image Base64-encoded image data
     * @param mimeType Image MIME type (e.g., "image/jpeg", "image/png")
     * @param userCountry ISO country code for location-aware web search (e.g., "DK" for Denmark)
     * @return Plain text response from AI with web search results
     */
    public String askSimpleQuestionWithImageAndWebSearch(String system,
                                                         String userInstructionText,
                                                         String base64Image,
                                                         String mimeType,
                                                         String userCountry) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", model);
            req.put("max_output_tokens", 4096);

            // Enable web search tool
            ArrayNode tools = req.putArray("tools");
            ObjectNode webSearch = tools.addObject();
            webSearch.put("type", "web_search");
            ObjectNode userLocation = webSearch.putObject("user_location");
            userLocation.put("type", "approximate");
            userLocation.put("country", userCountry != null ? userCountry : "DK");
            webSearch.put("search_context_size", "medium");

            // Enable reasoning for web search
            ObjectNode reasoning = req.putObject("reasoning");
            reasoning.put("effort", "medium");

            // Text format configuration
            ObjectNode text = req.putObject("text");
            ObjectNode format = text.putObject("format");
            format.put("type", "text");
            text.put("verbosity", "medium");

            // Store response and include reasoning/search sources
            req.put("store", true);
            ArrayNode include = req.putArray("include");
            include.add("reasoning.encrypted_content");
            include.add("web_search_call.action.sources");

            // Build input array with system + user (text + image)
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
            log.debugf("[OpenAIService] Sending response (web search + image). model=%s, country=%s, bodySize=%d",
                    model, userCountry, body.length());

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return "Validation error: OpenAI API returned status " + http.getStatus();
            }

            JsonNode root = objectMapper.readTree(payload);
            String result = extractOutputTextOrEmpty(root);

            // If result is empty or just "{}", return a fallback message
            if (result == null || result.isBlank() || result.equals("{}")) {
                return "Validation error: Unable to process image";
            }

            return result;

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (web search + image)", e);
            return "Validation error: " + e.getMessage();
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

    /**
     * Structured output + VISION + WEB SEARCH in a single Responses call.
     * - Uses JSON Schema via text.format (Responses API style)
     * - Optionally includes a base64 image (vision)
     * - Enables the built-in web_search tool
     */
    public String askWithSchemaImageAndWebSearch(String system,
                                                 String userInstructionText,
                                                 String base64Image,
                                                 String mimeType,
                                                 ObjectNode jsonSchema,
                                                 String schemaName,
                                                 String userCountry,
                                                 String refusalFallbackJson) {
        try {
            // Base JSON-schema request for Responses API
            ObjectNode req = baseSchemaRequest(jsonSchema, schemaName);

            // --- Web search tool config ---
            ArrayNode tools = req.putArray("tools");
            ObjectNode webSearch = tools.addObject();
            webSearch.put("type", "web_search");
            ObjectNode userLocation = webSearch.putObject("user_location");
            userLocation.put("type", "approximate");
            userLocation.put("country", userCountry != null ? userCountry : "DK");
            webSearch.put("search_context_size", "medium"); // low | medium | high

            // --- Reasoning (optional, used by many newer models) ---
            ObjectNode reasoning = req.putObject("reasoning");
            reasoning.put("effort", "medium");

            // --- Store + include reasoning and sources ---
            req.put("store", true);
            ArrayNode include = req.putArray("include");
            include.add("reasoning.encrypted_content");
            include.add("web_search_call.action.sources");

            // --- Input messages (text + optional image) ---
            ArrayNode input = req.putArray("input");

            if (system != null && !system.isBlank()) {
                ObjectNode sys = input.addObject();
                sys.put("role", "system");
                sys.put("content", system);
            }

            ObjectNode user = input.addObject();
            user.put("role", "user");

            ArrayNode content = objectMapper.createArrayNode();

            // Text part
            ObjectNode textPart = content.addObject();
            textPart.put("type", "input_text");
            textPart.put("text", userInstructionText == null ? "" : userInstructionText);

            // Optional image part (only if we actually have an image)
            if (base64Image != null && !base64Image.isBlank()) {
                ObjectNode imagePart = content.addObject();
                imagePart.put("type", "input_image");
                imagePart.put("image_url", toDataUrl(base64Image, mimeType));
            }

            user.set("content", content);

            String body = objectMapper.writeValueAsString(req);
            log.debugf(
                    "[OpenAIService] Sending response (json_schema + image + web_search). model=%s, bodySize=%d",
                    model, body.length()
            );

            Response http = openAIClient.createResponse("Bearer " + apiKey, "application/json", body);
            String payload = http.readEntity(String.class);

            if (http.getStatus() / 100 != 2) {
                // IMPORTANT: log body to see OpenAI's detailed error next time
                log.errorf("[OpenAIService] OpenAI error status=%d body=%s", http.getStatus(), payload);
                return refusalFallbackJson != null ? refusalFallbackJson : "{}";
            }

            JsonNode root = objectMapper.readTree(payload);
            String refusal = extractRefusal(root);
            if (refusal != null) {
                log.warnf("[OpenAIService] Model refusal detected: %s", refusal);
                return refusalFallbackJson != null ? refusalFallbackJson : "{}";
            }

            return extractOutputTextOrEmpty(root);

        } catch (Exception e) {
            log.error("[OpenAIService] Responses request failed (schema + image + web search)", e);
            return refusalFallbackJson != null ? refusalFallbackJson : "{}";
        }
    }


    /* ----------------- helpers ----------------- */

    private ObjectNode baseSchemaRequest(ObjectNode jsonSchema, String schemaName) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("model", model);
        req.put("max_output_tokens", 4096);

        // Responses API structured outputs:
        // text: {
        //   format: {
        //     type: "json_schema",
        //     name: "<schema name>",
        //     schema: { ... JSON Schema ... },
        //     strict: true
        //   }
        // }
        ObjectNode text = req.putObject("text");
        ObjectNode format = text.putObject("format");
        format.put("type", "json_schema");
        format.put("name", schemaName == null ? "schema" : schemaName);
        format.set("schema", jsonSchema);
        format.put("strict", true);

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
