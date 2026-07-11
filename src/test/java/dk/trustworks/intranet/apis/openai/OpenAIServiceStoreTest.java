package dk.trustworks.intranet.apis.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OpenAIServiceStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void explicitNoStoreOverloadSendsFalse_whileExistingOverloadKeepsPreviousShape() throws Exception {
        OpenAIClient client = mock(OpenAIClient.class);
        when(client.createResponse(anyString(), anyString(), anyString()))
                .thenAnswer(ignored -> successfulResponse());

        OpenAIService service = new OpenAIService();
        service.openAIClient = client;
        service.apiKey = "test-key";
        service.model = "default-model";

        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("ok");
        schema.putObject("properties").putObject("ok").put("type", "boolean");

        assertEquals("{\"ok\":true}", service.askQuestionWithSchema(
                "system", "user", schema, "test_schema", null, "gpt-5.4", 4096, false));
        assertEquals("{\"ok\":true}", service.askQuestionWithSchema(
                "system", "user", schema, "test_schema", null, "gpt-5.4", 4096));

        ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).createResponse(eq("Bearer test-key"), eq("application/json"), request.capture());

        JsonNode noStore = JSON.readTree(request.getAllValues().get(0));
        JsonNode legacy = JSON.readTree(request.getAllValues().get(1));
        assertTrue(noStore.has("store"));
        assertFalse(noStore.get("store").asBoolean());
        assertFalse(legacy.has("store"), "legacy callers must retain their prior request shape");
        assertEquals("gpt-5.4", noStore.get("model").asText());
        assertEquals(4096, noStore.get("max_output_tokens").asInt());
    }

    @Test
    void noStoreRefusalNon2xxAndTimeoutLikeFailureReturnUnusableSentinel() {
        OpenAIClient client = mock(OpenAIClient.class);
        Response refusal = mock(Response.class);
        when(refusal.getStatus()).thenReturn(200);
        when(refusal.readEntity(String.class)).thenReturn("{\"refusal\":\"cannot comply\"}");
        Response upstreamError = mock(Response.class);
        when(upstreamError.getStatus()).thenReturn(503);
        when(upstreamError.readEntity(String.class)).thenReturn("sensitive upstream diagnostic");
        when(client.createResponse(anyString(), anyString(), anyString()))
                .thenReturn(refusal, upstreamError)
                .thenThrow(new RuntimeException("read timeout"));

        OpenAIService service = new OpenAIService();
        service.openAIClient = client;
        service.apiKey = "test-key";
        service.model = "default-model";
        ObjectNode schema = JSON.createObjectNode().put("type", "object");

        for (int i = 0; i < 3; i++) {
            assertEquals("{}", service.askQuestionWithSchema(
                    "system", "user", schema, "test_schema", null, "gpt-5.4", 4096, false));
        }
        verify(client, times(3)).createResponse(anyString(), anyString(), anyString());
    }

    private static Response successfulResponse() {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(200);
        when(response.readEntity(String.class)).thenReturn("{\"output_text\":\"{\\\"ok\\\":true}\"}");
        return response;
    }
}
