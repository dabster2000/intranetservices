package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link OpenAIPortImpl} correctly delegates to the underlying
 * {@link OpenAIService}, hydrates prompts/schemas from the catalogs, and
 * surfaces empty/refusal payloads as
 * {@link OpenAIPort.OpenAIPortRefusalException}.
 */
@QuarkusTest
class OpenAIPortImplTest {

    @Inject
    OpenAIPort port;

    @InjectMock
    OpenAIService openAIService;

    @Test
    void generate_passesPromptAndSchemaToOpenAIService_returnsStructuredOutput() throws Exception {
        when(openAIService.askQuestionWithSchema(anyString(), anyString(), any(ObjectNode.class), anyString(), anyString()))
                .thenReturn("{\"firstName\":\"Alice\",\"evidence\":[]}");

        OpenAIPort.GenerateResult r = port.generate(
                "cv-extraction", "cv-extraction-v1",
                Map.of("cvText", "Alice Smith, Java developer..."),
                // outputSchema fetched from SchemaCatalog inside the port
                null);

        assertNotNull(r.outputJson());
        assertTrue(r.outputJson().contains("Alice"));
        verify(openAIService, times(1))
                .askQuestionWithSchema(anyString(), anyString(), any(ObjectNode.class), anyString(), anyString());
    }

    @Test
    void generate_throwsRefusalWhenOpenAIReturnsRefusalFallback() throws Exception {
        when(openAIService.askQuestionWithSchema(anyString(), anyString(), any(ObjectNode.class), anyString(), anyString()))
                .thenReturn("{}");  // empty object is the refusal-fallback signal

        assertThrows(OpenAIPort.OpenAIPortRefusalException.class, () ->
                port.generate("cv-extraction", "cv-extraction-v1",
                        Map.of("cvText", "REFUSE_THIS"), null));
    }
}
