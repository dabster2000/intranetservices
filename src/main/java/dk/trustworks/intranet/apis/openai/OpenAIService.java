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
    
    @ConfigProperty(name = "openai.model", defaultValue = "o1")
    String model;

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public String askQuestion(String question) {
        try {
            // Create JSON manually using Jackson
            ObjectNode requestJson = objectMapper.createObjectNode();
            requestJson.put("model", model);
            requestJson.put("temperature", 0.7);

            ArrayNode messagesArray = requestJson.putArray("messages");
            ObjectNode messageNode = messagesArray.addObject();
            messageNode.put("role", "user");
            messageNode.put("content", question);

            // Convert to JSON string
            String requestBody = objectMapper.writeValueAsString(requestJson);

            // Send request
            String response = openAIClient.createChatCompletion(
                    "Bearer " + apiKey,
                    "application/json",
                    requestBody
            );

            // Parse the response
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
}