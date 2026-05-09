package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Synchronous AI validator for the public onboarding upload endpoint.
 *
 * <p>Each call sends one image to OpenAI's vision-capable model with a
 * type-specific system prompt and a strict JSON schema. The schema asks for
 * four boolean checks ({@code isCorrectDocumentType}, {@code isDanish},
 * {@code isReadable}, {@code isValid}) plus a top-level {@code approved}
 * boolean and a short {@code reason}. After parsing, we recompute
 * {@code approved == AND(checks)} as a guardrail against the model
 * approving despite a sub-check being false.</p>
 *
 * <p>Failure mode is "fail closed": any transport error, refusal, or
 * unparsable response yields {@code approved=false} with a friendly reason
 * — see {@link #FALLBACK_REJECTED_JSON}. The caller blocks the upload.</p>
 */
@JBossLog
@ApplicationScoped
public class OnboardingDocumentValidationService {

    static final ObjectMapper MAPPER = new ObjectMapper();

    public record ValidationDecision(boolean approved, String reason) {}

    /**
     * Schema-conformant fallback returned when OpenAI refuses or fails.
     * Hard-rejected with a user-facing reason that points at HR.
     */
    static final String FALLBACK_REJECTED_JSON = """
        {
          "approved": false,
          "reason": "AI validation failed — please try again, or contact hr@trustworks.dk if it keeps failing.",
          "checks": {
            "isCorrectDocumentType": false,
            "isDanish": false,
            "isReadable": false,
            "isValid": false
          }
        }
        """;

    @Inject
    OpenAIService openAIService;

    /**
     * Build the strict JSON schema sent to OpenAI for all three document
     * types. Shared across prompts so the parser has one shape to handle.
     */
    static ObjectNode buildSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode props = schema.putObject("properties");

        props.putObject("approved").put("type", "boolean");

        ObjectNode reason = props.putObject("reason");
        reason.put("type", "string");
        reason.put("minLength", 5);
        reason.put("maxLength", 240);

        ObjectNode checks = props.putObject("checks");
        checks.put("type", "object");
        checks.put("additionalProperties", false);
        ObjectNode checksProps = checks.putObject("properties");
        checksProps.putObject("isCorrectDocumentType").put("type", "boolean");
        checksProps.putObject("isDanish").put("type", "boolean");
        checksProps.putObject("isReadable").put("type", "boolean");
        checksProps.putObject("isValid").put("type", "boolean");
        ArrayNode checksRequired = checks.putArray("required");
        checksRequired.add("isCorrectDocumentType");
        checksRequired.add("isDanish");
        checksRequired.add("isReadable");
        checksRequired.add("isValid");

        ArrayNode required = schema.putArray("required");
        required.add("approved");
        required.add("reason");
        required.add("checks");

        return schema;
    }
}
