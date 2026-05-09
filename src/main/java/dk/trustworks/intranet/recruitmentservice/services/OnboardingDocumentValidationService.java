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

    static final String SYSTEM_PROMPT_DRIVERS_LICENSE = """
            You are validating a Danish driver's licence (kørekort) submitted as part of a hiring onboarding.

            Decide whether the image can be accepted, by evaluating four checks. Set each boolean strictly:

            - isCorrectDocumentType: true ONLY if the image clearly shows a driver's licence (a credit-card-sized
              card with photo, name, date of birth, licence categories like A/B/C, and a licence number).
              False for passports, ID cards, sundhedskort, or any other document.

            - isDanish: true ONLY if the document is a Danish driver's licence. Indicators include the wording
              "KØREKORT" / "DRIVING LICENCE" with a Danish issuing authority, the small "DK" country code in the
              EU flag, or Danish category labels. Older paper Danish licences (pink folded paper) are also valid.

            - isReadable: true ONLY if the photo is sharp enough that the name, date of birth, expiry date,
              and licence number are all clearly legible without guessing. Reject blurry, dark, glare-covered,
              rotated, or partially-cropped images.

            - isValid: true ONLY if the licence has not expired. Compare the printed expiry date (field 4b) to
              today's date. If the expiry date is unreadable, set isValid=false.

            approved = all four booleans true.

            reason: one short sentence (<=240 chars). If approved, say so plainly. If rejected, name the SINGLE
            biggest issue and tell the user what to do next (e.g. "We could not read the expiry date — please
            upload a sharper photo with all four corners visible"). Never expose internal field names.

            Return ONLY the JSON object specified by the schema. No markdown, no commentary.
            """;

    static final String SYSTEM_PROMPT_HEALTH_INSURANCE = """
            You are validating a Danish health-insurance card (sundhedskort, also known as the yellow card / det
            gule sygesikringsbevis) submitted as part of a hiring onboarding.

            - isCorrectDocumentType: true ONLY if the image shows a sundhedskort — a yellow plastic card showing
              the holder's name, CPR number, address, and the assigned general practitioner (læge). False for
              driver's licences, passports, EU health insurance cards (the blue EHIC), or anything else.

            - isDanish: true ONLY if it is a Danish sundhedskort issued by a Danish kommune / Region. Look for
              Danish text such as "Sundhedskort", a Danish address, and a 10-digit CPR number formatted DDMMYY-XXXX.
              The blue EU EHIC (Det blå EU-sygesikringsbevis) is NOT acceptable here — set isDanish=false and
              explain in the reason.

            - isReadable: true ONLY if the holder's name, CPR number, address, and GP information are clearly
              legible. Reject blurry, glare-covered, partly-cropped, or low-resolution photos.

            - isValid: true unless the card itself shows an expiry/validity date that is in the past. Sundhedskort
              do not normally print an expiry — in that case treat isValid=true if the card is otherwise legible.

            approved = all four booleans true.

            reason: one short sentence (<=240 chars). If rejected, name the SINGLE biggest issue and tell the
            user what to do next. Never expose CPR digits in the reason.

            Return ONLY the JSON object specified by the schema. No markdown, no commentary.
            """;

    static final String SYSTEM_PROMPT_CRIMINAL_RECORD = """
            You are validating a Danish certificate of criminal record (straffeattest / privat straffeattest)
            submitted as part of a hiring onboarding.

            - isCorrectDocumentType: true ONLY if the document is a straffeattest issued by Danish police
              (Politiet / Rigspolitiet). Indicators: the heading "Straffeattest" or "Privat straffeattest", the
              named subject's full name and CPR, an issue date (udstedt), and a Politiet logo or letterhead.
              False for course certificates, references, child-protection certificates (børneattest — that is
              a different document), or any other paper.

            - isDanish: true ONLY if it is issued by Danish Politiet/Rigspolitiet (Danish text, Danish authority).

            - isReadable: true ONLY if the subject's name, the issue date, and the body text are clearly legible.
              Reject blurry, partial, or rotated images. PDFs printed and re-photographed are fine if sharp.

            - isValid: true ONLY if the issue date (udstedt) is no more than 3 calendar months before today.
              If the issue date is unreadable, set isValid=false.

            approved = all four booleans true.

            reason: one short sentence (<=240 chars). If rejected, name the SINGLE biggest issue and tell the
            user what to do (e.g. "Your straffeattest is more than 3 months old — please request a fresh one
            from politi.dk and upload it"). Never expose CPR digits.

            Return ONLY the JSON object specified by the schema. No markdown, no commentary.
            """;

    static String systemPromptFor(OnboardingDocumentType type) {
        return switch (type) {
            case DRIVERS_LICENSE  -> SYSTEM_PROMPT_DRIVERS_LICENSE;
            case HEALTH_INSURANCE -> SYSTEM_PROMPT_HEALTH_INSURANCE;
            case CRIMINAL_RECORD  -> SYSTEM_PROMPT_CRIMINAL_RECORD;
        };
    }

    /**
     * Parse a strict-JSON-Schema response from OpenAI into a
     * {@link ValidationDecision}. Applies the guardrail
     * {@code approved == AND(checks)} after parsing.
     *
     * <p>Any of: empty input, unparsable input, missing {@code checks}
     * object, or {@code approved} disagreeing with the AND of its checks
     * yields a rejected decision with a generic friendly reason. The
     * caller never has to deal with malformed AI output.</p>
     */
    static ValidationDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ValidationDecision(false, "AI validation returned no response — please try again.");
        }
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            log.warnf("[OnboardingValidate] Could not parse AI response: %s",
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            return new ValidationDecision(false,
                    "AI validation returned an unreadable response — please try again.");
        }
        com.fasterxml.jackson.databind.JsonNode checks = node.path("checks");
        if (!checks.isObject()) {
            return new ValidationDecision(false,
                    "AI validation returned an incomplete response — please try again.");
        }
        boolean expected = checks.path("isCorrectDocumentType").asBoolean(false)
                && checks.path("isDanish").asBoolean(false)
                && checks.path("isReadable").asBoolean(false)
                && checks.path("isValid").asBoolean(false);
        boolean approved = node.path("approved").asBoolean(false);
        String reason = node.path("reason").asText("");
        if (reason == null || reason.isBlank()) {
            reason = expected
                    ? "Document accepted."
                    : "Document could not be validated — please re-upload a clearer image.";
        }
        if (approved != expected) {
            // Guardrail: trust the per-check booleans, not the top-level claim.
            log.warnf("[OnboardingValidate] approved/checks mismatch: approved=%s expected=%s",
                    approved, expected);
            return new ValidationDecision(false,
                    "Validation inconsistency — please re-upload the document.");
        }
        return new ValidationDecision(approved, reason);
    }
}
