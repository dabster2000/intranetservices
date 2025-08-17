package dk.trustworks.intranet.apis.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class OpenAIService {
    
    @Inject
    @RestClient
    OpenAIClient openAIClient;
    
    @ConfigProperty(name = "openai.api.key")
    String apiKey;
    
    @ConfigProperty(name = "openai.model", defaultValue = "gpt-5-mini")
    String model;

    @Inject
    dk.trustworks.intranet.expenseservice.ai.TaxonomyService taxonomyService;

    @Inject
    OpenAIRateLimiter rateLimiter;

    @ConfigProperty(name = "openai.rate.retry.max-attempts", defaultValue = "6")
    int retryMaxAttempts;

    @ConfigProperty(name = "openai.rate.retry.base-delay-ms", defaultValue = "400")
    long retryBaseDelayMs;

    @ConfigProperty(name = "openai.rate.retry.max-delay-ms", defaultValue = "4000")
    long retryMaxDelayMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public String askQuestion(String question) {
        try {
            ObjectNode requestJson = objectMapper.createObjectNode();
            requestJson.put("model", model);
            requestJson.put("temperature", 0.7);

            ArrayNode messagesArray = requestJson.putArray("messages");
            ObjectNode messageNode = messagesArray.addObject();
            messageNode.put("role", "user");
            messageNode.put("content", question);

            String requestBody = objectMapper.writeValueAsString(requestJson);

            String response = openAIClient.createChatCompletion(
                    "Bearer " + apiKey,
                    "application/json",
                    requestBody
            );

            JsonNode responseJson = objectMapper.readTree(response);
            String content = responseJson.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("No response generated.");

            return content;
        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    public ExpenseMetadata extractExpenseMetadata(String base64Image, String businessHints) {
        try {
            return rateLimiter.callThrottled(() -> doExtractWithRetry(base64Image, businessHints));
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract expense metadata", e);
        }
    }

    private ExpenseMetadata doExtractWithRetry(String base64Image, String businessHints) throws Exception {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                ObjectNode req = objectMapper.createObjectNode();
                req.put("model", model); // vision-capable model
                req.put("temperature", 0.0);
                req.put("max_tokens", 800);

                // ---- Structured Outputs via response_format ----
                ObjectNode responseFormat = req.putObject("response_format");
                responseFormat.put("type", "json_schema");
                ObjectNode js = responseFormat.putObject("json_schema");
                js.put("name", "ExpenseMetadata");
                js.put("strict", false);

                ObjectNode schema = js.putObject("schema");
                schema.put("type", "object");
                schema.put("additionalProperties", false);

                ObjectNode props = schema.putObject("properties");
                props.putObject("merchantName").put("type", "string");

                ObjectNode mc = props.putObject("merchantCategory");
                mc.put("type", "string");
                try {
                    if (taxonomyService != null && taxonomyService.isReady()) {
                        ArrayNode enumArr = mc.putArray("enum");
                        for (String id : taxonomyService.getAllCategoryIds()) enumArr.add(id);
                    }
                } catch (Exception ignore) { }

                props.putObject("confidence").put("type", "number");
                props.putObject("expenseDate").put("type", "string").put("format", "date");
                props.putObject("currency").put("type", "string");
                props.putObject("totalAmount").put("type", "number");
                props.putObject("subtotalAmount").put("type", "number");
                props.putObject("vatAmount").put("type", "number");
                props.putObject("paymentMethod").put("type", "string");
                props.putObject("country").put("type", "string");
                props.putObject("city").put("type", "string");
                props.putObject("drinksTotal").put("type", "number");
                props.putObject("alcoholTotal").put("type", "number");
                props.putObject("coffeeTotal").put("type", "number");
                props.putObject("juiceTotal").put("type", "number");
                props.putObject("waterTotal").put("type", "number");
                props.putObject("softDrinkTotal").put("type", "number");

                ObjectNode tags = props.putObject("tags");
                tags.put("type", "array");
                tags.putObject("items").put("type", "string");

                ObjectNode lineItems = props.putObject("lineItems");
                lineItems.put("type", "array");
                ObjectNode item = lineItems.putObject("items");
                item.put("type", "object");
                item.put("additionalProperties", false);

                ObjectNode liProps = item.putObject("properties");
                liProps.putObject("description").put("type", "string");
                liProps.putObject("quantity").put("type", "number");
                liProps.putObject("unitPrice").put("type", "number");
                liProps.putObject("total").put("type", "number");

                ObjectNode ic = liProps.putObject("itemCategory");
                ic.put("type", "string");
                try {
                    if (taxonomyService != null && taxonomyService.isReady()) {
                        ArrayNode enumArr = ic.putArray("enum");
                        for (String id : taxonomyService.getAllCategoryIds()) enumArr.add(id);
                    }
                } catch (Exception ignore) { }

                ArrayNode liRequired = item.putArray("required");
                liRequired.add("description");
                liRequired.add("quantity");
                liRequired.add("unitPrice");
                liRequired.add("total");
                liRequired.add("itemCategory");

                ArrayNode requiredRoot = schema.putArray("required");
                requiredRoot.add("merchantName");
                requiredRoot.add("merchantCategory");
                requiredRoot.add("totalAmount");

                // ---- Chat Completions: text + image_url (data-URL) ----
                ArrayNode messages = req.putArray("messages");
                ObjectNode user = messages.addObject();
                user.put("role", "user");
                ArrayNode content = user.putArray("content");

                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text",
                        "Extract structured expense data from this receipt image.\n" +
                                businessHints +
                                "\nReturn valid JSON only."
                );

                String mime = detectMimeFromBase64(base64Image);
                ObjectNode img = content.addObject();
                img.put("type", "image_url");
                ObjectNode imageUrl = img.putObject("image_url");
                imageUrl.put("url", "data:" + mime + ";base64," + base64Image.trim());

                String requestBody = objectMapper.writeValueAsString(req);
                String response = openAIClient.createChatCompletion(
                        "Bearer " + apiKey,
                        "application/json",
                        requestBody
                );

                JsonNode json = objectMapper.readTree(response);
                String contentText = json.path("choices").path(0).path("message").path("content").asText("{}");
                return objectMapper.readValue(contentText, ExpenseMetadata.class);
            } catch (RuntimeException ex) {
                if (!isRetryable(ex) || attempt >= retryMaxAttempts) throw ex;
                long sleep = computeBackoffDelay(ex, attempt);
                sleepQuietly(sleep);
            }
        }
    }

    private boolean isRetryable(RuntimeException ex) {
        String msg = ex.getMessage();
        if (msg == null) return false;
        if (msg.contains("OpenAI API error 429")) return true;
        if (msg.contains("OpenAI API error 5")) return true; // 5xx
        return false;
    }

    private long computeBackoffDelay(RuntimeException ex, int attempt) {
        long hinted = parseSuggestedWaitMs(ex.getMessage());
        if (hinted > 0) return Math.min(hinted + jitter(100), retryMaxDelayMs);
        long exp = (long) (retryBaseDelayMs * Math.pow(2, attempt - 1));
        exp = Math.min(exp, retryMaxDelayMs);
        return exp + jitter(150);
    }

    private static long parseSuggestedWaitMs(String msg) {
        if (msg == null) return -1;
        var m = java.util.regex.Pattern.compile("try again in\\s+(\\d+)ms", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(msg);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (Exception ignored) {}
        }
        return -1;
    }

    private static long jitter(int bound) { return (long) (Math.random() * Math.max(bound, 1)); }
    private static void sleepQuietly(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }

    private static String detectMimeFromBase64(String base64) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(base64);
            if (bytes.length >= 4) {
                // PNG
                if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return "image/png";
                // JPEG
                if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
                // GIF
                if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return "image/gif";
                // WebP: RIFF....WEBP
                if (bytes.length >= 12 && bytes[0]=='R' && bytes[1]=='I' && bytes[2]=='F' && bytes[3]=='F' &&
                        bytes[8]=='W' && bytes[9]=='E' && bytes[10]=='B' && bytes[11]=='P') return "image/webp";
                // PDF (not ideal for image_url, but detect)
                if (bytes[0] == 0x25 && bytes[1]==0x50 && bytes[2]==0x44 && bytes[3]==0x46) return "application/pdf";
            }
        } catch (Exception ignore) {}
        return "image/png";
    }
}