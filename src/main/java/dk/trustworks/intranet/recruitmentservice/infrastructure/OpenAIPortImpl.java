package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.domain.ai.prompts.PromptCatalog;
import dk.trustworks.intranet.recruitmentservice.domain.ai.schemas.SchemaCatalog;
import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * Live implementation of {@link OpenAIPort} that wraps the project-wide
 * {@link OpenAIService} and pulls system prompts + JSON schemas from the
 * versioned recruitment catalogs.
 *
 * <p>Resolution: registered with {@code @Alternative @Priority(10)} so it
 * beats {@link NoopOpenAIPort} ({@code @Priority(1)}) at runtime. Tests that
 * need a fake can either register their own higher-priority alternative or
 * use {@code @InjectMock OpenAIService} to control the underlying call.
 *
 * <p>Refusal handling: {@code OpenAIService} returns either {@code "{}"} or
 * the caller-provided fallback when the model refuses or the HTTP call
 * fails. We treat both as a refusal here and surface
 * {@link OpenAIPortRefusalException} so the worker can mark the artifact
 * permanently failed instead of retrying.
 */
@JBossLog
@ApplicationScoped
@Alternative
@Priority(10)  // beats NoopOpenAIPort@Priority(1)
public class OpenAIPortImpl implements OpenAIPort {

    @Inject
    OpenAIService openAIService;

    @Inject
    PromptCatalog prompts;

    @Inject
    SchemaCatalog schemas;

    @ConfigProperty(name = "openai.model", defaultValue = "gpt-5-nano")
    String model;

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public GenerateResult generate(String promptId, String promptVersion,
                                   Map<String, Object> inputs, String outputSchema)
            throws OpenAIPortException, OpenAIPortRefusalException {

        String systemPrompt = prompts.load(promptVersion);
        // outputSchema arg may be null — fetch from catalog by promptVersion if so
        String schemaJson = outputSchema != null ? outputSchema : schemas.load(promptVersion);

        ObjectNode schemaNode;
        String userMsg;
        try {
            schemaNode = (ObjectNode) json.readTree(schemaJson);
            userMsg = json.writeValueAsString(inputs);
        } catch (Exception e) {
            throw new OpenAIPortException("failed to prepare prompt inputs/schema", e);
        }

        long start = System.currentTimeMillis();
        log.infof("[OpenAIPort] generate kind=%s version=%s model=%s inputBytes=%d",
                promptId, promptVersion, model, userMsg.length());

        String result = openAIService.askQuestionWithSchema(
                systemPrompt, userMsg, schemaNode, promptId, "{}");

        long ms = System.currentTimeMillis() - start;
        log.infof("[OpenAIPort] response kind=%s ms=%d outBytes=%d",
                promptId, ms, result == null ? 0 : result.length());

        if (result == null || result.equals("{}")) {
            throw new OpenAIPortRefusalException(
                    "model returned empty/refusal payload for " + promptId);
        }

        // Slice 2 stores evidence inside the same output JSON (per schema).
        // Empty evidenceJson kept here for shape; readers consume from outputJson.
        return new GenerateResult(result, "[]", model);
    }
}
