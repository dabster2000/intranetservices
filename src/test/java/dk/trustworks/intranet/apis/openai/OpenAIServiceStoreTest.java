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

    /**
     * The 2026-08-06 production incident: the ai-intake reactor's OpenAI call died with a
     * transport failure, but the store=false branch logged only the outermost wrapper class
     * ("errorType=ProcessingException; details suppressed") — so timeout vs connection reset
     * vs serialization bug could not be told apart from the logs. The suppressed branch must
     * now log the cause-chain classes and the JVM-generated transport message, while still
     * never attaching a stack trace or echoing prompt content.
     */
    @Test
    void transportFailureOnNoStorePathLogsCauseChain_withoutStackOrPayload() {
        OpenAIClient client = mock(OpenAIClient.class);
        when(client.createResponse(anyString(), anyString(), anyString()))
                .thenThrow(new jakarta.ws.rs.ProcessingException(
                        "RESTEASY004655: Unable to invoke request",
                        new java.net.SocketTimeoutException("Read timed out")));

        OpenAIService service = new OpenAIService();
        service.openAIClient = client;
        service.apiKey = "test-key";
        service.model = "default-model";
        ObjectNode schema = JSON.createObjectNode().put("type", "object");

        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger julLogger = Logger.getLogger(OpenAIService.class.getName());
        julLogger.addHandler(capture);
        try {
            assertEquals("{}", service.askQuestionWithSchema(
                    "SECRET-SYSTEM-PROMPT", "SECRET-USER-PROMPT", schema, "test_schema",
                    null, "gpt-5.6-terra", 8192, false));
        } finally {
            julLogger.removeHandler(capture);
        }

        LogRecord failure = records.stream()
                .filter(r -> r.getMessage().contains("Responses request failed (schema"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("suppressed branch must still log; captured: "
                        + records.stream().map(LogRecord::getMessage).toList()));
        assertTrue(failure.getMessage().contains(
                        "ProcessingException <- SocketTimeoutException(Read timed out)"),
                failure.getMessage());
        assertNull(failure.getThrown(), "no-store path must not attach a stack trace");
        assertFalse(failure.getMessage().contains("SECRET-"), "must not echo prompt content");
    }

    /**
     * The discrimination that keeps the chain privacy-safe: Jackson exceptions are
     * IOExceptions whose messages can embed payload fragments, so they must render
     * class-name-only even though socket-level IOExceptions render their message.
     */
    @Test
    void jacksonFailureStaysClassNameOnlyOnNoStorePath() {
        OpenAIClient client = mock(OpenAIClient.class);
        when(client.createResponse(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException(new com.fasterxml.jackson.core.JsonParseException(
                        null, "candidate-cv-fragment leaked into the parser message")));

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
                    "system", "user", schema, "test_schema", null, "gpt-5.6-terra", 8192, false));
        } finally {
            julLogger.removeHandler(capture);
        }

        String failure = logged.stream()
                .filter(m -> m.contains("Responses request failed (schema"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("suppressed branch must still log; captured: " + logged));
        assertTrue(failure.contains("RuntimeException <- JsonParseException"), failure);
        assertFalse(failure.contains("candidate-cv-fragment"),
                "Jackson messages can carry payload content and must stay suppressed: " + failure);
    }

    @Test
    void describeFailureChain_rendersTransportMessages_andTerminatesOnCyclicCauses() {
        assertEquals("ProcessingException <- SocketTimeoutException(Read timed out)",
                OpenAIService.describeFailureChain(new jakarta.ws.rs.ProcessingException(
                        "RESTEASY004655: Unable to invoke request",
                        new java.net.SocketTimeoutException("Read timed out"))));
        assertEquals("UnknownHostException(api.openai.com)",
                OpenAIService.describeFailureChain(new java.net.UnknownHostException("api.openai.com")));

        Exception inner = new Exception("inner");
        Exception outer = new Exception("outer", inner);
        inner.initCause(outer); // 2-cycle — must terminate via the depth cap, not hang
        assertTrue(OpenAIService.describeFailureChain(outer).startsWith("Exception <- Exception"));
    }

    // ---- describeErrorEnvelope: diagnosable without leaking the prompt ---------
    //
    // The 2026-08-27 ai-intake schema 400 logged only "status=400 (body suppressed)",
    // which made the failure undiagnosable. These pin the two halves of the fix:
    // the schema diagnostics must come through, and the prompt must not.

    @Test
    void schemaValidation400_reportsTheRejectedField() {
        String body = "{\"error\":{\"message\":\"Invalid schema for response_format "
                + "'RecruitmentAiIntake': In context=('properties','brief'), 'maxLength' is not "
                + "permitted.\",\"type\":\"invalid_request_error\",\"param\":\"text.format.schema\","
                + "\"code\":null}}";

        String described = OpenAIService.describeErrorEnvelope(body);

        assertTrue(described.contains("type=invalid_request_error"), described);
        assertTrue(described.contains("param=text.format.schema"), described);
        assertTrue(described.contains("code=<none>"), described);
        assertTrue(described.contains("'properties','brief'"),
                "the whole point is naming the rejected field: " + described);
    }

    @Test
    void contentPolicy400_withholdsTheMessage_becauseItCanQuoteThePrompt() {
        String body = "{\"error\":{\"message\":\"Your input was blocked: 'Ansoegerens CV naevner "
                + "en sygemelding'\",\"type\":\"invalid_request_error\","
                + "\"code\":\"content_policy_violation\",\"param\":null}}";

        String described = OpenAIService.describeErrorEnvelope(body);

        assertTrue(described.contains("code=content_policy_violation"), described);
        assertTrue(described.contains("message=<suppressed"), described);
        assertFalse(described.contains("sygemelding"),
                "a content-policy message can quote the CV and must never be logged: " + described);
    }

    @Test
    void aNonJsonBody_isReportedByLengthOnly() {
        // An ALB/proxy HTML page is not OpenAI's envelope, so nothing in it is safe.
        String described = OpenAIService.describeErrorEnvelope(
                "<html><body>502 Bad Gateway: upstream sent Ansoeger Jens Hansen</body></html>");

        assertTrue(described.startsWith("unparseable body ("), described);
        assertFalse(described.contains("Jens Hansen"), described);
    }

    @Test
    void envelopeFields_areCappedAndKeptToOneLogLine() {
        String body = "{\"error\":{\"message\":\"" + "x".repeat(4000)
                + "\",\"type\":\"invalid_request_error\",\"param\":\"a\\nb\",\"code\":null}}";

        String described = OpenAIService.describeErrorEnvelope(body);

        assertFalse(described.contains("\n"), "a newline would forge extra log records: " + described);
        assertTrue(described.length() < 1200, "must stay one bounded line, was " + described.length());
    }

    @Test
    void anAbsentOrEmptyBody_doesNotBlowUp() {
        assertEquals("no body", OpenAIService.describeErrorEnvelope(null));
        assertEquals("no body", OpenAIService.describeErrorEnvelope("   "));
        assertTrue(OpenAIService.describeErrorEnvelope("{\"ok\":true}").startsWith("no error envelope"));
    }

    private static Response successfulResponse() {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(200);
        when(response.readEntity(String.class)).thenReturn("{\"output_text\":\"{\\\"ok\\\":true}\"}");
        return response;
    }
}
