package dk.trustworks.intranet.apis.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    /**
     * The 2026-08-01 staging incident: a reasoning model answered 2xx having spent the whole
     * output budget on hidden reasoning, so there was no output text. That path used to return
     * the literal "{}" with NO log line at all — the only silent branch in the method — which
     * is why the recruitment 500s could not be explained from the logs. It must now diagnose
     * itself, using only the structural fields the API reports (never any content, since this
     * path also carries store=false candidate/HR data).
     */
    @Test
    void emptyOutputOn2xxIsDiagnosedInTheLog_withoutLeakingContent() {
        OpenAIClient client = mock(OpenAIClient.class);
        Response exhausted = mock(Response.class);
        when(exhausted.getStatus()).thenReturn(200);
        when(exhausted.readEntity(String.class)).thenReturn("""
                {"status":"incomplete",
                 "incomplete_details":{"reason":"max_output_tokens"},
                 "output":[],
                 "usage":{"output_tokens":2000,
                          "output_tokens_details":{"reasoning_tokens":2000}}}""");
        when(client.createResponse(anyString(), anyString(), anyString())).thenReturn(exhausted);

        OpenAIService service = new OpenAIService();
        service.openAIClient = client;
        service.apiKey = "test-key";
        service.model = "default-model";
        ObjectNode schema = JSON.createObjectNode().put("type", "object");

        List<String> logged = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger julLogger = Logger.getLogger(OpenAIService.class.getName());
        julLogger.addHandler(capture);
        try {
            assertEquals("{}", service.askQuestionWithSchema(
                    "system", "user", schema, "test_schema", null, "gpt-5-nano", 2000, false));
        } finally {
            julLogger.removeHandler(capture);
        }

        String diagnostic = logged.stream()
                .filter(m -> m.contains("NO output text"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "empty-output path must log a diagnostic; captured: " + logged));
        assertTrue(diagnostic.contains("max_output_tokens"), diagnostic);
        assertTrue(diagnostic.contains("reasoning_tokens=2000"), diagnostic);
        assertTrue(diagnostic.contains("gpt-5-nano"), diagnostic);
        assertFalse(diagnostic.contains("system"), "must not echo prompt content");
        assertFalse(diagnostic.contains("user"), "must not echo prompt content");
    }

    /**
     * The reasoning node is the half of the fix that keeps a reasoning model from spending
     * the whole output budget on thinking — but it only works if it actually reaches the
     * wire, and it must NOT appear when no effort is given (the gpt-4o family rejects an
     * unexpected reasoning node with HTTP 400, which is how a scanned-CV vision call would
     * break). Every other test in the repo mocks OpenAIService, so this is the only place
     * the built request body is inspected.
     */
    @Test
    void reasoningEffortReachesTheRequestBody_andIsOmittedWhenAbsent() throws Exception {
        OpenAIClient client = mock(OpenAIClient.class);
        when(client.createResponse(anyString(), anyString(), anyString()))
                .thenAnswer(ignored -> successfulResponse());

        OpenAIService service = new OpenAIService();
        service.openAIClient = client;
        service.apiKey = "test-key";
        service.model = "default-model";
        ObjectNode schema = JSON.createObjectNode().put("type", "object");

        service.askQuestionWithSchema("system", "user", schema, "s", null,
                "gpt-5.6-terra", 8192, false, "low");
        service.askQuestionWithSchema("system", "user", schema, "s", null,
                "gpt-5.6-terra", 8192, false, null);
        service.askQuestionWithSchema("system", "user", schema, "s", null,
                "gpt-5.6-terra", 8192, false, "   ");
        // The pre-existing 8-arg overload must keep its exact previous request shape.
        service.askQuestionWithSchema("system", "user", schema, "s", null,
                "gpt-4o-mini", 4096, false);

        ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
        verify(client, times(4)).createResponse(anyString(), anyString(), request.capture());
        List<String> bodies = request.getAllValues();

        JsonNode withEffort = JSON.readTree(bodies.get(0));
        assertTrue(withEffort.hasNonNull("reasoning"), "effort must reach the wire");
        assertEquals("low", withEffort.get("reasoning").get("effort").asText());
        assertEquals(8192, withEffort.get("max_output_tokens").asInt());

        assertFalse(JSON.readTree(bodies.get(1)).has("reasoning"), "null effort omits the node");
        assertFalse(JSON.readTree(bodies.get(2)).has("reasoning"), "blank effort omits the node");
        assertFalse(JSON.readTree(bodies.get(3)).has("reasoning"),
                "the legacy overload must not gain a reasoning node");
    }

    private static Response successfulResponse() {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(200);
        when(response.readEntity(String.class)).thenReturn("{\"output_text\":\"{\\\"ok\\\":true}\"}");
        return response;
    }
}
