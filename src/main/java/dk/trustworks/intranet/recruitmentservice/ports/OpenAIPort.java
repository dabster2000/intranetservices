package dk.trustworks.intranet.recruitmentservice.ports;

import java.util.Map;

/**
 * Slice 2 contract for AI generation. Outbox-backed; called by AiArtifactWorker.
 * Real impl lives at infrastructure/OpenAIPortImpl. Test fakes implement this directly.
 */
public interface OpenAIPort {

    /**
     * Generate a structured artifact output using the given prompt + JSON schema.
     *
     * @param promptId      stable id for the prompt template (e.g. "cv-extraction")
     * @param promptVersion version tag, recorded on the artifact for reproducibility
     * @param inputs        canonical input map; serialised into the user message
     * @param outputSchema  JSON-schema string the result MUST conform to
     * @return result with {@code outputJson} and {@code evidenceJson} (both raw JSON strings)
     * @throws OpenAIPortException on transient failures (caller decides retry)
     * @throws OpenAIPortRefusalException on hard model refusal (do not retry)
     */
    GenerateResult generate(String promptId, String promptVersion,
                            Map<String, Object> inputs, String outputSchema)
        throws OpenAIPortException, OpenAIPortRefusalException;

    record GenerateResult(String outputJson, String evidenceJson, String modelName) {}

    class OpenAIPortException extends Exception {
        public OpenAIPortException(String msg, Throwable cause) { super(msg, cause); }
        public OpenAIPortException(String msg) { super(msg); }
    }

    class OpenAIPortRefusalException extends Exception {
        public OpenAIPortRefusalException(String msg) { super(msg); }
    }
}
