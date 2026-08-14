package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.ai.AvailabilitySchedulingPrompts;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Method B free-text availability extraction (plan §12.3): one
 * OpenAI strict-schema call ({@code store:false}) turning one Slack
 * message into the spec §13.3 structure, followed by DETERMINISTIC
 * backend validation — the model proposes, {@link #validate} disposes.
 * Nothing the model returns reaches persistence or the planner without
 * passing the allowlist, the window bound and the sanity caps here.
 * <p>
 * Model: the AI-intake extraction tier ({@code
 * dk.trustworks.recruitment.ai.extraction-model}) — deliberately NOT
 * the global {@code openai.model} (gpt-5-nano's reasoning spend answers
 * {@code {}} for structured output; ground truth 2026-08-12 plan) —
 * with the same pinned low reasoning effort and {@code Optional}
 * injection trap-avoidance as {@code AiIntakeGenerationService}.
 * <p>
 * Threading: {@link #extract} MUST run outside any transaction (the
 * §P9 M1 rule — the guard throws) — callers run it on the
 * {@code ManagedExecutor} after the dispatch commit, the P25 offload
 * shape.
 */
@JBossLog
@ApplicationScoped
public class AvailabilityExtractionService {

    static final String SCHEMA_NAME = "availability_extraction";
    static final int MAX_OUTPUT_TOKENS = 4096;

    /** Sanity cap: one message never yields more intervals than this. */
    static final int MAX_CONSTRAINTS = 20;

    /** Constraints may reach this far outside the request window (plan §12.3). */
    static final int WINDOW_SLACK_DAYS = 7;

    /** Below this lowest per-constraint confidence, confirmation is forced. */
    static final BigDecimal MIN_AUTOCONFIRM_CONFIDENCE = new BigDecimal("0.75");

    @Inject
    OpenAIService openAIService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.ai.extraction-model",
            defaultValue = "gpt-5.6-terra")
    String extractionModel;

    /** Optional so an EMPTY env value means "omit the reasoning node"
     * (the SRCFG00040 trap — see AiIntakeGenerationService). */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.extraction-reasoning-effort",
            defaultValue = "low")
    Optional<String> extractionReasoningEffort;

    /** One raw extracted interval, exactly as the model claimed it. */
    public record RawConstraint(AvailabilityConstraintType type,
                                LocalDateTime start, LocalDateTime end,
                                BigDecimal confidence) {
    }

    /** The parsed model output — claims, not yet facts. */
    public record Extraction(String language, String intent, String timezone,
                             LocalDate coveredFrom, LocalDate coveredTo,
                             List<RawConstraint> constraints, List<String> ambiguities,
                             boolean requiresConfirmation, String clarifyingQuestion) {

        static Extraction unknown() {
            return new Extraction("da", AvailabilitySchedulingPrompts.INTENT_UNKNOWN,
                    "Europe/Copenhagen", null, null, List.of(), List.of(), true, null);
        }
    }

    /**
     * The deterministic verdict on one extraction. {@code rejectReason}
     * != null ⇒ the submission is UNKNOWN/invalid: persisted REJECTED
     * for the manual-review list, never as constraints.
     */
    public record Validated(String intent, String language, String timezone,
                            LocalDate coveredFrom, LocalDate coveredTo,
                            List<RawConstraint> constraints,
                            boolean requiresConfirmation,
                            BigDecimal minConfidence,
                            List<String> ambiguities,
                            String clarifyingQuestion,
                            String rejectReason) {
    }

    /** Run the model. NEVER inside a transaction. */
    public Extraction extract(LocalDate today, LocalDate windowStart,
                              LocalDate windowEnd, String message) {
        if (QuarkusTransaction.isActive()) {
            // The §P9 M1 rule: never hold a pooled connection across the model call.
            throw new IllegalStateException("extract must not be called inside a transaction");
        }
        String json = openAIService.askQuestionWithSchema(
                AvailabilitySchedulingPrompts.systemPrompt(),
                AvailabilitySchedulingPrompts.userPrompt(today, windowStart, windowEnd, message),
                AvailabilitySchedulingPrompts.schema(),
                SCHEMA_NAME,
                AvailabilitySchedulingPrompts.REFUSAL_FALLBACK_JSON,
                extractionModel, MAX_OUTPUT_TOKENS, false,
                extractionReasoningEffort.filter(e -> !e.isBlank()).orElse(null));
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject() || node.isEmpty()) {
                return Extraction.unknown();
            }
            List<RawConstraint> constraints = new ArrayList<>();
            for (JsonNode c : node.path("constraints")) {
                AvailabilityConstraintType type;
                LocalDateTime start;
                LocalDateTime end;
                try {
                    type = AvailabilityConstraintType.valueOf(c.path("type").asText());
                    start = LocalDateTime.parse(c.path("start").asText());
                    end = LocalDateTime.parse(c.path("end").asText());
                } catch (Exception badRow) {
                    // One malformed interval poisons the submission — a
                    // partial reading confirmed by the interviewer would
                    // silently drop what they actually said.
                    return Extraction.unknown();
                }
                constraints.add(new RawConstraint(type, start, end,
                        BigDecimal.valueOf(c.path("confidence").asDouble(0))
                                .setScale(2, RoundingMode.HALF_UP)));
            }
            List<String> ambiguities = new ArrayList<>();
            node.path("ambiguities").forEach(a -> ambiguities.add(a.asText()));
            return new Extraction(
                    node.path("language").asText("da"),
                    node.path("intent").asText(AvailabilitySchedulingPrompts.INTENT_UNKNOWN),
                    node.path("timezone").asText("Europe/Copenhagen"),
                    parseDateOrNull(node.path("coveredFrom")),
                    parseDateOrNull(node.path("coveredTo")),
                    constraints, ambiguities,
                    node.path("requiresConfirmation").asBoolean(true),
                    node.path("clarifyingQuestion").isTextual()
                            ? node.path("clarifyingQuestion").asText() : null);
        } catch (Exception e) {
            log.warn("Availability extraction returned unusable JSON — treating as UNKNOWN", e);
            return Extraction.unknown();
        }
    }

    private static LocalDate parseDateOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Deterministic validation (pure; DB-free tested)
    // ------------------------------------------------------------------

    /** Structural reject reasons (never free text). */
    public static final String REJECT_UNKNOWN_INTENT = "UNKNOWN_INTENT";
    public static final String REJECT_NO_CONSTRAINTS = "NO_CONSTRAINTS";
    public static final String REJECT_TOO_MANY_CONSTRAINTS = "TOO_MANY_CONSTRAINTS";
    public static final String REJECT_OUTSIDE_WINDOW = "OUTSIDE_WINDOW";
    public static final String REJECT_INVALID_INTERVAL = "INVALID_INTERVAL";
    public static final String REJECT_INVALID_COVERED_RANGE = "INVALID_COVERED_RANGE";

    /**
     * The plan §12.3 gate, applied AFTER the model: intent allowlisted,
     * dates inside window ± {@value WINDOW_SLACK_DAYS} days, constraint
     * count sane — anything else rejects. Also forces confirmation
     * (D9's text-side rule) whenever the extraction left doubt:
     * ambiguities, a non-Copenhagen timezone, or low confidence.
     * Routed/button intents pass through without constraints — their
     * disposition is the caller's switch, not evidence.
     */
    public static Validated validate(Extraction extraction,
                                     LocalDate windowStart, LocalDate windowEnd) {
        String language = "en".equals(extraction.language()) ? "en" : "da";
        String intent = AvailabilitySchedulingPrompts.ALL_INTENTS.contains(extraction.intent())
                ? extraction.intent()
                : AvailabilitySchedulingPrompts.INTENT_UNKNOWN;

        boolean availability = AvailabilitySchedulingPrompts.AVAILABILITY_INTENTS.contains(intent);
        if (!availability) {
            String reject = AvailabilitySchedulingPrompts.INTENT_UNKNOWN.equals(intent)
                    ? REJECT_UNKNOWN_INTENT : null;
            return new Validated(intent, language, extraction.timezone(), null, null,
                    List.of(), true, null, extraction.ambiguities(),
                    extraction.clarifyingQuestion(), reject);
        }

        List<RawConstraint> constraints = extraction.constraints();
        if (constraints.isEmpty()) {
            return rejected(extraction, intent, language, REJECT_NO_CONSTRAINTS);
        }
        if (constraints.size() > MAX_CONSTRAINTS) {
            return rejected(extraction, intent, language, REJECT_TOO_MANY_CONSTRAINTS);
        }
        LocalDate earliestAllowed = windowStart.minusDays(WINDOW_SLACK_DAYS);
        LocalDate latestAllowed = windowEnd.plusDays(WINDOW_SLACK_DAYS);
        BigDecimal minConfidence = null;
        for (RawConstraint constraint : constraints) {
            if (!constraint.start().isBefore(constraint.end())) {
                return rejected(extraction, intent, language, REJECT_INVALID_INTERVAL);
            }
            if (constraint.start().toLocalDate().isBefore(earliestAllowed)
                    || constraint.end().toLocalDate().isAfter(latestAllowed)) {
                return rejected(extraction, intent, language, REJECT_OUTSIDE_WINDOW);
            }
            if (minConfidence == null || constraint.confidence().compareTo(minConfidence) < 0) {
                minConfidence = constraint.confidence();
            }
        }

        // The covered range: the model's claim, defaulted to the span of
        // the constraints when absent — evidence never applies wider than
        // what was actually said (spec §11.5 visible-range rule).
        LocalDate coveredFrom = extraction.coveredFrom();
        LocalDate coveredTo = extraction.coveredTo();
        if (coveredFrom == null || coveredTo == null) {
            coveredFrom = constraints.stream().map(c -> c.start().toLocalDate())
                    .min(LocalDate::compareTo).orElseThrow();
            coveredTo = constraints.stream().map(c -> c.end().toLocalDate())
                    .max(LocalDate::compareTo).orElseThrow();
        }
        if (coveredFrom.isAfter(coveredTo)
                || coveredFrom.isBefore(earliestAllowed) || coveredTo.isAfter(latestAllowed)) {
            return rejected(extraction, intent, language, REJECT_INVALID_COVERED_RANGE);
        }

        boolean forcedConfirmation = !extraction.ambiguities().isEmpty()
                || !"Europe/Copenhagen".equals(extraction.timezone())
                || minConfidence.compareTo(MIN_AUTOCONFIRM_CONFIDENCE) < 0;
        return new Validated(intent, language, extraction.timezone(),
                coveredFrom, coveredTo, List.copyOf(constraints),
                extraction.requiresConfirmation() || forcedConfirmation,
                minConfidence, extraction.ambiguities(),
                extraction.clarifyingQuestion(), null);
    }

    private static Validated rejected(Extraction extraction, String intent,
                                      String language, String reason) {
        return new Validated(intent, language, extraction.timezone(), null, null,
                List.of(), true, null, extraction.ambiguities(),
                extraction.clarifyingQuestion(), reason);
    }
}
