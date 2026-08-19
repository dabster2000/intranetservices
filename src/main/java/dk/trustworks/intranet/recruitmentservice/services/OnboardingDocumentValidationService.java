package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;

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
 *
 * <p>Fail-closed does not mean "blame the document". An infrastructure
 * failure — {@link OpenAIService} returning its {@code "{}"} sentinel for a
 * non-2xx, a transport exception, or a 2xx that carried no output text — is
 * detected by {@link #isServiceFailureSentinel(String)} and reported with
 * {@link #SERVICE_UNAVAILABLE_REASON} and an ERROR log, never as a verdict on
 * the uploaded kørekort / sundhedskort / straffeattest. A model REFUSAL takes a
 * third route: {@link OpenAIService} answers with our own
 * {@link #FALLBACK_REJECTED_JSON}, not with the sentinel, so
 * {@link #isRefusalFallback(String)} catches it separately — otherwise a
 * refusal would look exactly like an ordinary rejection on the INFO line,
 * which is the blind spot the sentinel split set out to close.</p>
 */
@JBossLog
@ApplicationScoped
public class OnboardingDocumentValidationService {

    static final ObjectMapper MAPPER = new ObjectMapper();

    static final ObjectNode SCHEMA = buildSchema();

    /** Shorter than this counts as "the model did not answer the reason field". */
    static final int REASON_MIN_LENGTH = 5;

    /** Hard cap applied in Java — see {@link #buildSchema()} for why not in the schema. */
    static final int REASON_MAX_LENGTH = 240;

    /** Used when the model config property is absent OR present-but-empty. */
    static final String DEFAULT_ONBOARDING_DOC_MODEL = "gpt-4o-mini";

    /** Used when the token-budget config property is absent OR present-but-empty. */
    static final int DEFAULT_ONBOARDING_DOC_MAX_OUTPUT_TOKENS = 8192;

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

    /**
     * User-facing reason for "we could not reach a verdict", as opposed to
     * "we looked at your document and said no". Kept in one place so the
     * transport-exception path, the empty-output path and
     * {@link #FALLBACK_REJECTED_JSON} all speak with one voice.
     */
    static final String SERVICE_UNAVAILABLE_REASON =
            "AI validation failed — please try again, or contact hr@trustworks.dk if it keeps failing.";

    @Inject
    OpenAIService openAIService;

    /**
     * The document-validation vision model. Deliberately NOT the global
     * {@code openai.model}: that default is gpt-5-nano, a reasoning-class model
     * which spends its {@code max_output_tokens} budget on hidden reasoning
     * first and answers 2xx with NO output text for image + strict-schema
     * requests — the trap documented on {@code openai.vision-model} in
     * application.yml. On this @PermitAll gate that empty output would reach a
     * new hire as a rejected kørekort with no human override anywhere, so the
     * call is pinned to a proven non-reasoning vision model.
     *
     * <p>Injected as {@code Optional<String>} on purpose. A plain {@code String}
     * with a {@code defaultValue} survives the property being ABSENT, but not it
     * being PRESENT-BUT-EMPTY: an operator unpinning the model with
     * {@code DK_TRUSTWORKS_RECRUITMENT_AI_ONBOARDING_DOC_MODEL=} supplies an
     * empty value, {@code defaultValue} does not rescue it, and Quarkus aborts at
     * STARTUP (SRCFG00040) — the whole backend, not just this gate.
     * {@link #resolvedModel()} maps both absent and blank onto
     * {@link #DEFAULT_ONBOARDING_DOC_MODEL} instead, which is also what
     * {@code OpenAIService} does with a blank {@code modelOverride}.</p>
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.onboarding-doc-model")
    Optional<String> onboardingDocModel;

    /**
     * Output budget for that call. Moves TOGETHER with the model: pointing
     * {@code onboarding-doc-model} at a reasoning-class model without raising
     * this reproduces exactly the empty-output failure above. {@code Optional}
     * for the same present-but-empty startup hazard as the model property.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.onboarding-doc-max-output-tokens")
    Optional<Integer> onboardingDocMaxOutputTokens;

    /** Model actually used: the config value if it carries text, else the pinned default. */
    String resolvedModel() {
        return onboardingDocModel
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(DEFAULT_ONBOARDING_DOC_MODEL);
    }

    /** Output budget actually used; absent or non-positive falls back to the default. */
    int resolvedMaxOutputTokens() {
        return onboardingDocMaxOutputTokens
                .filter(v -> v > 0)
                .orElse(DEFAULT_ONBOARDING_DOC_MAX_OUTPUT_TOKENS);
    }

    /**
     * Build the strict JSON schema sent to OpenAI for all three document types.
     * Shared across prompts so the parser has one shape to handle.
     *
     * <p>{@code reason} deliberately declares NO {@code minLength} /
     * {@code maxLength}. Strict structured output accepts only a subset of JSON
     * Schema, and a keyword the validator rejects comes back as HTTP 400, which
     * {@link OpenAIService} turns into its {@code "{}"} sentinel — on this
     * fail-closed @PermitAll gate that would block EVERY onboarding upload, of
     * every document type, until a human noticed. The production calls that did
     * exercise those two keywords all ran on gpt-5-nano; this call is pinned to
     * gpt-4o-mini, which has never been tried against them, so that evidence
     * does not transfer. Same convention as
     * {@code ConsultantProfilePrompts.schema()}: state the length in the PROMPT,
     * enforce it in Java ({@link #capReason}), and keep the wire schema to
     * keywords that cannot cause a schema rejection.</p>
     */
    static ObjectNode buildSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode props = schema.putObject("properties");

        props.putObject("approved").put("type", "boolean");

        ObjectNode reason = props.putObject("reason");
        reason.put("type", "string");
        reason.put("description",
                "One short sentence, " + REASON_MIN_LENGTH + "-" + REASON_MAX_LENGTH
                        + " characters. Longer answers are shortened by the caller.");

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
              TODAY'S DATE AS STATED IN THE USER MESSAGE. That stated date is authoritative — never substitute
              your own idea of the current date. If the expiry date is unreadable, set isValid=false.

            approved = all four booleans true.

            reason: one short sentence, between 5 and 240 characters. If approved, say so plainly. If rejected,
            name the SINGLE biggest issue and tell the user what to do next (e.g. "We could not read the expiry
            date — please upload a sharper photo with all four corners visible"). Never expose internal field names.

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

            - isValid: true unless the card itself shows an expiry/validity date earlier than TODAY'S DATE AS
              STATED IN THE USER MESSAGE. Use that stated date, never your own idea of the current date.
              Sundhedskort do not normally print an expiry — in that case treat isValid=true if the card is
              otherwise legible.

            approved = all four booleans true.

            reason: one short sentence, between 5 and 240 characters. If rejected, name the SINGLE biggest issue
            and tell the user what to do next. Never expose CPR digits in the reason.

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

            - isValid: true ONLY if the issue date (udstedt) is no more than 3 calendar months before TODAY'S DATE
              AS STATED IN THE USER MESSAGE. Count those 3 months back from the stated date, never from your own
              idea of the current date. If the issue date is unreadable, set isValid=false.

            approved = all four booleans true.

            reason: one short sentence, between 5 and 240 characters. If rejected, name the SINGLE biggest issue
            and tell the user what to do (e.g. "Your straffeattest is more than 3 months old — please request a
            fresh one from politi.dk and upload it"). Never expose CPR digits.

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
     * The user-message text. Carries the REAL current date, because the model has
     * no other way to know it: DRIVERS_LICENSE decides {@code isValid} by comparing
     * the printed expiry to "today", and CRIMINAL_RECORD by counting 3 months back
     * from "today". Without this line "today" is wherever the model's training data
     * stopped, so an already-expired kørekort or a stale straffeattest passes — and
     * every pin to an older model pushes that implicit "today" further into the
     * past. ISO-8601 so there is no DD/MM vs MM/DD ambiguity.
     */
    static String userInstructionFor(LocalDate today) {
        return "Today's date is " + today + " (ISO-8601, yyyy-MM-dd). Use exactly this date for every date "
                + "comparison — do not rely on your own notion of the current date. "
                + "Validate this image and return the JSON object specified by the schema.";
    }

    /**
     * True when {@code raw} is {@link OpenAIService}'s "I could not answer"
     * sentinel rather than a model verdict.
     *
     * <p>{@code OpenAIService} returns the literal {@code "{}"} for a non-2xx
     * status, for any thrown transport/serialisation failure, and for a 2xx
     * whose body carried no output text (the reasoning-model empty-output
     * trap). {@code "{}"} can never be a genuine answer here: the strict schema
     * makes {@code approved}, {@code reason} and {@code checks} all required,
     * so an empty object is unambiguously an infrastructure failure.</p>
     */
    static boolean isServiceFailureSentinel(String raw) {
        return raw == null || raw.isBlank() || "{}".equals(raw.trim());
    }

    /**
     * True when {@code raw} is our own {@link #FALLBACK_REJECTED_JSON}, handed
     * back by {@link OpenAIService} because the MODEL REFUSED the request.
     *
     * <p>The refusal path does NOT return the {@code "{}"} sentinel — it returns
     * whatever {@code refusalFallbackJson} the caller supplied. Ours parses to a
     * perfectly ordinary rejection, so without this check a refusal is logged at
     * INFO as "one more rejected document" and disappears into the normal
     * rejection rate.</p>
     */
    static boolean isRefusalFallback(String raw) {
        return raw != null && FALLBACK_REJECTED_JSON.trim().equals(raw.trim());
    }

    /**
     * Enforce the reason-length expectation in Java rather than in the wire
     * schema — see {@link #buildSchema()}. Below {@link #REASON_MIN_LENGTH} the
     * model effectively did not answer, so a generic sentence is substituted;
     * above {@link #REASON_MAX_LENGTH} the text is clipped so the user-facing
     * string stays one line.
     */
    static String capReason(String reason, boolean approved) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() < REASON_MIN_LENGTH) {
            return approved
                    ? "Document accepted."
                    : "Document could not be validated — please re-upload a clearer image.";
        }
        if (r.length() > REASON_MAX_LENGTH) {
            return r.substring(0, REASON_MAX_LENGTH - 1).trim() + "…";
        }
        return r;
    }

    /**
     * Parse a strict-JSON-Schema response from OpenAI into a
     * {@link ValidationDecision}. Applies the guardrail
     * {@code approved == AND(checks)} after parsing.
     *
     * <p>Any of: empty input, unparsable input, missing {@code checks}
     * object, or {@code approved} disagreeing with the AND of its checks
     * yields a rejected decision with a generic friendly reason. The
     * caller never has to deal with malformed AI output. Input that is the
     * {@link #isServiceFailureSentinel(String) service-failure sentinel} is
     * separated out first and reported as a service failure.</p>
     */
    static ValidationDecision parseDecision(String raw) {
        if (isServiceFailureSentinel(raw)) {
            // Infrastructure failure, not a rejected document. Guarded here too
            // so the distinction holds for every caller of this static.
            return new ValidationDecision(false, SERVICE_UNAVAILABLE_REASON);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            log.warnf("[OnboardingValidate] Could not parse AI response: %s",
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            return new ValidationDecision(false,
                    "AI validation returned an unreadable response — please try again.");
        }
        JsonNode checks = node.path("checks");
        if (!checks.isObject()) {
            return new ValidationDecision(false,
                    "AI validation returned an incomplete response — please try again.");
        }
        boolean expected = checks.path("isCorrectDocumentType").asBoolean(false)
                && checks.path("isDanish").asBoolean(false)
                && checks.path("isReadable").asBoolean(false)
                && checks.path("isValid").asBoolean(false);
        boolean approved = node.path("approved").asBoolean(false);
        // Length is enforced here, NOT by the wire schema — see buildSchema().
        String reason = capReason(node.path("reason").asText(""), expected);
        if (approved != expected) {
            // Guardrail: trust the per-check booleans, not the top-level claim.
            log.warnf("[OnboardingValidate] approved/checks mismatch: approved=%s expected=%s",
                    approved, expected);
            return new ValidationDecision(false,
                    "Validation inconsistency — please re-upload the document.");
        }
        return new ValidationDecision(approved, reason);
    }

    /**
     * Validate one uploaded document against the type-specific prompt.
     *
     * <p>Returns a rejected {@link ValidationDecision} on any failure path —
     * transport error, refusal, malformed JSON, or guardrail mismatch. The
     * caller never has to handle exceptions.</p>
     *
     * <p>Failures we caused are logged at ERROR and carry
     * {@link #SERVICE_UNAVAILABLE_REASON}, so they neither read as a verdict on
     * the document nor hide inside the normal rejection rate on the INFO line.</p>
     */
    public ValidationDecision validate(OnboardingDocumentType type, byte[] bytes, String mimeType) {
        if (type == null || bytes == null || bytes.length == 0) {
            return new ValidationDecision(false, "No document provided to validate.");
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String system = systemPromptFor(type);
        String model = resolvedModel();
        String raw;
        try {
            // The model is pinned away from the global openai.model (gpt-5-nano) on
            // purpose. A reasoning-class model burns its max_output_tokens budget on
            // hidden reasoning and answers 2xx with NO output text for image + strict-
            // schema requests — the failure documented on openai.vision-model in
            // application.yml. That empty output parses to a rejection, so a perfectly
            // valid kørekort would be refused on a @PermitAll gate with no human
            // override.
            //
            // This binds to the 10-arg (..., modelOverride, maxOutputTokens, store)
            // overload on purpose. That overload routes through
            // askWithSchemaAndImageInternal, which forwards reasoningEffort=null and
            // imageDetail=null to askWithSchemaAndImagesInternal — a byte-identical
            // request to the 12-arg overload called with two trailing nulls. Using the
            // older overload keeps this fail-closed gate independent of the newer one
            // landing. (The gpt-4o family rejects a non-null reasoning node with HTTP
            // 400 anyway, so the extra knobs have nothing to offer here.)
            //
            // store=false because the image is a person's identity papers or criminal
            // record: it stops OpenAI retaining the response as application state, and
            // switches OpenAIService onto its suppressed-logging branches so an upstream
            // error body or a refusal text can never echo a CPR number into our logs.
            // The trade-off, stated plainly: those same branches also suppress the
            // upstream 400 BODY, which is exactly the diagnostic we would want if the
            // strict-schema validator ever started rejecting this schema. If this gate
            // begins answering SERVICE_UNAVAILABLE_REASON for every upload, the status
            // code is in the log but the reason is not — reproduce the call off this
            // @PermitAll path with store=true to read it.
            raw = openAIService.askWithSchemaAndImage(
                    system,
                    userInstructionFor(LocalDate.now()),
                    base64,
                    mimeType,
                    SCHEMA,
                    "OnboardingDocValidation",
                    FALLBACK_REJECTED_JSON,
                    model,
                    resolvedMaxOutputTokens(),
                    false);
        } catch (RuntimeException e) {
            log.errorf(e, "[OnboardingValidate] OpenAI call failed for type=%s model=%s",
                    type, model);
            return new ValidationDecision(false, SERVICE_UNAVAILABLE_REASON);
        }
        if (isServiceFailureSentinel(raw)) {
            // Never tell a new hire their document was rejected because our
            // upstream call failed. ERROR (not the INFO below) so this is
            // alarmable and cannot hide inside the normal rejection rate.
            log.errorf("[OnboardingValidate] OpenAI returned no usable output for type=%s model=%s "
                            + "— infrastructure failure, not a document rejection", type, model);
            return new ValidationDecision(false, SERVICE_UNAVAILABLE_REASON);
        }
        if (isRefusalFallback(raw)) {
            // The model REFUSED; OpenAIService handed our own fallback JSON back
            // instead of the "{}" sentinel. It parses as an ordinary rejection, so
            // it must be logged loudly here or it vanishes into the rejection rate
            // on the INFO line below.
            log.errorf("[OnboardingValidate] OpenAI REFUSED the request for type=%s model=%s "
                            + "— refusal fallback returned, not a verdict on the document", type, model);
            return new ValidationDecision(false, SERVICE_UNAVAILABLE_REASON);
        }
        ValidationDecision d = parseDecision(raw);
        log.infof("[OnboardingValidate] type=%s approved=%s reasonLen=%d model=%s",
                type, d.approved(), d.reason() == null ? 0 : d.reason().length(), model);
        return d;
    }
}
