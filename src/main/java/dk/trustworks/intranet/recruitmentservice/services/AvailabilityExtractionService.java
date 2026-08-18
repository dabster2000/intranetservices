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
import java.time.LocalTime;
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
    static final String IMAGE_SCHEMA_NAME = "availability_image_extraction";
    static final int MAX_OUTPUT_TOKENS = 4096;

    /**
     * The image path's budget. Far larger than the text path's because a
     * reasoning-class vision model spends this SAME budget on hidden reasoning
     * first: raising the model without raising this reproduces exactly the
     * empty-structured-output failure documented on {@code openai.vision-model}.
     * Also has to carry a daysRead array for up to ~10 visible days.
     */
    static final int IMAGE_MAX_OUTPUT_TOKENS = 24576;

    /** Independent transcriptions of the same images (plan: double-read). */
    static final int IMAGE_READ_PASSES = 2;

    /**
     * Total transcription attempts allowed to REACH {@value #IMAGE_READ_PASSES}
     * successes. Security review 2026-08-18 (finding 5): retrying only on
     * DISAGREEMENT left the ordinary failure mode — one pass erroring on a
     * timeout or a provider 5xx — falling through to the uncorroborated
     * single-pass path, which is materially the risk the double-read exists to
     * close. A transient error now costs another attempt instead of costing
     * corroboration.
     */
    static final int IMAGE_READ_MAX_ATTEMPTS = 4;

    /** Sanity cap: one message never yields more intervals than this. */
    static final int MAX_CONSTRAINTS = 20;

    /** Constraints may reach this far outside the request window (plan §12.3). */
    static final int WINDOW_SLACK_DAYS = 7;

    /** Below this lowest per-constraint confidence, confirmation is forced. */
    static final BigDecimal MIN_AUTOCONFIRM_CONFIDENCE = new BigDecimal("0.75");

    /** The vision cap (plan §13.1 — the input_image contract's 20 MB). */
    public static final int IMAGE_MAX_BYTES = 20 * 1024 * 1024;

    /**
     * Magic-byte MIME sniff for the Phase 13 allowlist — jpeg/png/gif/
     * webp, the {@code input_image} formats. Returns null for anything
     * else. Slack's {@code mimetype} field is a CLAIM and never
     * consulted (the ExpenseAIValidationService posture, bytes-side).
     */
    public static String sniffImageMime(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "image/gif";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

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

    /**
     * The calendar-image model. Deliberately NOT the global
     * {@code openai.vision-model}: that property is shared with expense receipt
     * OCR and is pinned to gpt-4o-mini precisely because the vision path could
     * not send a reasoning node. Reading a calendar grid is spatial arithmetic
     * and needs a reasoning tier, so this path gets its own knob.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.image-model",
            defaultValue = "gpt-5.6-terra")
    String imageModel;

    /** Optional for the same SRCFG00040 reason; MUST be empty for gpt-4o-family. */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.image-reasoning-effort",
            defaultValue = "medium")
    Optional<String> imageReasoningEffort;

    /** {@code high} stops the API downsampling the grid away. Empty = API default. */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.image-detail",
            defaultValue = "high")
    Optional<String> imageDetail;

    /** One raw extracted interval, exactly as the model claimed it. */
    public record RawConstraint(AvailabilityConstraintType type,
                                LocalDateTime start, LocalDateTime end,
                                BigDecimal confidence) {
    }

    /**
     * The parsed model output — claims, not yet facts. {@code axis} and
     * {@code daysRead} are the v2 image-path transcription and are null/empty
     * on the text path.
     */
    public record Extraction(String language, String intent, String timezone,
                             LocalDate coveredFrom, LocalDate coveredTo,
                             List<RawConstraint> constraints, List<String> ambiguities,
                             boolean requiresConfirmation, String clarifyingQuestion,
                             AvailabilityImageReading.Axis axis,
                             List<AvailabilityImageReading.DayRead> daysRead) {

        /**
         * The text path's shape: no image transcription. Keeps the
         * nine-argument form every pre-v2 call site and test already uses.
         */
        public Extraction(String language, String intent, String timezone,
                          LocalDate coveredFrom, LocalDate coveredTo,
                          List<RawConstraint> constraints, List<String> ambiguities,
                          boolean requiresConfirmation, String clarifyingQuestion) {
            this(language, intent, timezone, coveredFrom, coveredTo, constraints,
                    ambiguities, requiresConfirmation, clarifyingQuestion, null, List.of());
        }

        static Extraction unknown() {
            return new Extraction("da", AvailabilitySchedulingPrompts.INTENT_UNKNOWN,
                    "Europe/Copenhagen", null, null, List.of(), List.of(), true, null,
                    null, List.of());
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
                            String rejectReason,
                            String readTrust) {

        /**
         * The text path and every pre-v2 caller: no image trust codes. Keeps the
         * eleven-argument shape those call sites already use.
         */
        public Validated(String intent, String language, String timezone,
                         LocalDate coveredFrom, LocalDate coveredTo,
                         List<RawConstraint> constraints, boolean requiresConfirmation,
                         BigDecimal minConfidence, List<String> ambiguities,
                         String clarifyingQuestion, String rejectReason) {
            this(intent, language, timezone, coveredFrom, coveredTo, constraints,
                    requiresConfirmation, minConfidence, ambiguities, clarifyingQuestion,
                    rejectReason, null);
        }
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
        return parse(json);
    }

    /**
     * Phase 13 v2: the vision path. ALL images from one message go into ONE
     * call so the model can reason across them — adjacent weeks, or an all-day
     * band that starts in one crop and continues past the edge of the next.
     * <p>
     * Three things changed from v1, and they must move together:
     * a reasoning-class model, a {@code medium} reasoning effort, and a budget
     * ({@value #IMAGE_MAX_OUTPUT_TOKENS}) large enough that hidden reasoning
     * does not starve the structured output. Raising the model alone reproduces
     * the documented empty-output trap.
     * <p>
     * NEVER inside a transaction.
     */
    public Extraction extractFromImages(LocalDate today, LocalDate windowStart,
                                        LocalDate windowEnd, String message,
                                        List<OpenAIService.ImageInput> images) {
        if (QuarkusTransaction.isActive()) {
            throw new IllegalStateException(
                    "extractFromImages must not be called inside a transaction");
        }
        String json = openAIService.askWithSchemaAndImages(
                AvailabilitySchedulingPrompts.imageSystemPrompt(),
                AvailabilitySchedulingPrompts.userPrompt(today, windowStart, windowEnd, message),
                images,
                AvailabilitySchedulingPrompts.imageSchema(),
                IMAGE_SCHEMA_NAME,
                AvailabilitySchedulingPrompts.IMAGE_REFUSAL_FALLBACK_JSON,
                imageModel, IMAGE_MAX_OUTPUT_TOKENS, false,
                imageReasoningEffort.filter(e -> !e.isBlank()).orElse(null),
                imageDetail.filter(d -> !d.isBlank()).orElse(null));
        return parse(json);
    }

    /** Single-image convenience — the message path always uses the list form. */
    public Extraction extractFromImage(LocalDate today, LocalDate windowStart,
                                       LocalDate windowEnd, String message,
                                       byte[] imageBytes, String mimeType) {
        return extractFromImages(today, windowStart, windowEnd, message,
                List.of(new OpenAIService.ImageInput(
                        java.util.Base64.getEncoder().encodeToString(imageBytes), mimeType)));
    }

    /**
     * The whole image pipeline: transcribe {@value #IMAGE_READ_PASSES} times
     * independently, keep only days at least two passes agree about, derive the
     * busy intervals from that consensus, and judge whether the result looks
     * like a misread. A third pass runs only when the first two disagree.
     * <p>
     * An honest limit: agreement proves the reading is STABLE, not that it is
     * correct — a model that is confidently and consistently wrong will agree
     * with itself. That is why the deterministic tells in
     * {@link AvailabilityImageReading#assess} matter independently; they catch
     * the systematic-wrong case double-reading cannot.
     * <p>
     * NEVER inside a transaction.
     */
    public ImageReading readImages(LocalDate today, LocalDate windowStart,
                                   LocalDate windowEnd, String message,
                                   List<OpenAIService.ImageInput> images) {
        List<Extraction> passes = new ArrayList<>();
        for (int attempt = 0;
                passes.size() < IMAGE_READ_PASSES && attempt < IMAGE_READ_MAX_ATTEMPTS;
                attempt++) {
            Extraction extraction = safeRead(today, windowStart, windowEnd, message, images);
            if (extraction != null) {
                passes.add(extraction);
            }
        }
        if (passes.isEmpty()) {
            return ImageReading.failed();
        }

        List<List<AvailabilityImageReading.DayRead>> readings =
                passes.stream().map(Extraction::daysRead).toList();
        AvailabilityImageReading.Consensus consensus =
                AvailabilityImageReading.reconcile(readings);

        if (!consensus.unresolved().isEmpty() && passes.size() >= 2) {
            // Only now is a third call worth its latency: a tie-breaker for the
            // days the first two passes read differently.
            Extraction tieBreak = safeRead(today, windowStart, windowEnd, message, images);
            if (tieBreak != null) {
                passes.add(tieBreak);
                consensus = AvailabilityImageReading.reconcile(
                        passes.stream().map(Extraction::daysRead).toList());
            }
        }

        Extraction primary = passes.getFirst();
        AvailabilityImageReading.Derived derived =
                AvailabilityImageReading.derive(primary.axis(), consensus.days());
        LocalDate from = null;
        LocalDate to = null;
        for (LocalDate date : AvailabilityImageReading.datesSeen(consensus.days())) {
            if (from == null || date.isBefore(from)) {
                from = date;
            }
            if (to == null || date.isAfter(to)) {
                to = date;
            }
        }
        AvailabilityImageReading.Trust trust =
                AvailabilityImageReading.assess(primary.axis(), derived, from, to);
        return new ImageReading(primary, consensus, derived, trust, passes.size(), from, to,
                message != null && !message.isBlank());
    }

    /**
     * The persisted trust codes: the deterministic tells plus whether a second
     * pass corroborated the reading. Audit and card disclosure only.
     */
    static String trustCodes(ImageReading reading) {
        List<String> codes = new ArrayList<>(reading.trust().reasons());
        if (!reading.corroborated()) {
            codes.add("NOT_CORROBORATED");
        }
        if (codes.isEmpty()) {
            return null;
        }
        String joined = String.join(",", codes);
        return joined.length() > 160 ? joined.substring(0, 160) : joined;
    }

    private Extraction safeRead(LocalDate today, LocalDate windowStart, LocalDate windowEnd,
                                String message, List<OpenAIService.ImageInput> images) {
        try {
            Extraction extraction =
                    extractFromImages(today, windowStart, windowEnd, message, images);
            // A pass that read nothing contributes nothing to consensus.
            return extraction.daysRead().isEmpty() && extraction.constraints().isEmpty()
                    ? null : extraction;
        } catch (Exception e) {
            log.warnf("Method B image transcription pass failed: %s", e.getMessage());
            return null;
        }
    }

    /** One completed image pipeline run — claims plus the deterministic verdict. */
    public record ImageReading(Extraction primary,
                               AvailabilityImageReading.Consensus consensus,
                               AvailabilityImageReading.Derived derived,
                               AvailabilityImageReading.Trust trust,
                               int passes,
                               LocalDate coveredFrom, LocalDate coveredTo,
                               boolean hasAccompanyingText) {

        static ImageReading failed() {
            return new ImageReading(Extraction.unknown(),
                    new AvailabilityImageReading.Consensus(List.of(), List.of(), false),
                    new AvailabilityImageReading.Derived(List.of(), List.of(), List.of(), List.of()),
                    new AvailabilityImageReading.Trust(true, List.of("NO_READING")),
                    0, null, null, false);
        }

        public boolean corroborated() {
            return passes >= 2 && consensus.unresolved().isEmpty();
        }
    }

    /**
     * Turn one image pipeline run into the planner-facing verdict. BUSY comes
     * ONLY from the derived transcription — never from the model's own
     * constraints array — so the model cannot assert busy time it did not
     * transcribe. Soft and exclusive constraints (AVAILABLE_ONLY, PREFERRED,
     * AVOID) still come from the model, because those describe the accompanying
     * TEXT rather than the picture.
     * <p>
     * A reading whose every day came back free is VALID, not empty: it means
     * "nothing is booked", and the card says so. Only a run that read nothing
     * at all rejects.
     */
    public static Validated validateImage(ImageReading reading,
                                          LocalDate windowStart, LocalDate windowEnd) {
        Extraction primary = reading.primary();
        String language = "en".equals(primary.language()) ? "en" : "da";
        String intent = AvailabilitySchedulingPrompts.ALL_INTENTS.contains(primary.intent())
                ? primary.intent()
                : AvailabilitySchedulingPrompts.INTENT_UNKNOWN;
        if (!AvailabilitySchedulingPrompts.AVAILABILITY_INTENTS.contains(intent)) {
            return new Validated(intent, language, primary.timezone(), null, null,
                    List.of(), true, null, primary.ambiguities(),
                    primary.clarifyingQuestion(),
                    AvailabilitySchedulingPrompts.INTENT_UNKNOWN.equals(intent)
                            ? REJECT_UNKNOWN_INTENT : null);
        }

        List<String> ambiguities = new ArrayList<>(primary.ambiguities());
        reading.derived().ambiguities().stream()
                .filter(a -> !ambiguities.contains(a))
                .forEach(ambiguities::add);
        if (!reading.consensus().unresolved().isEmpty()) {
            ambiguities.add("Nogle dage blev læst forskelligt ved gentagen læsning "
                    + "og er derfor ikke registreret.");
        }
        if (reading.passes() == 1) {
            ambiguities.add("Læsningen kunne ikke bekræftes ved en anden gennemlæsning.");
        }

        List<RawConstraint> constraints = new ArrayList<>();
        for (AvailabilityImageReading.Interval interval : reading.derived().busy()) {
            constraints.add(new RawConstraint(AvailabilityConstraintType.BUSY,
                    interval.start(), interval.end(),
                    interval.confidence() == null ? BigDecimal.ONE
                            : interval.confidence().setScale(2, RoundingMode.HALF_UP)));
        }
        // Text-derived, non-busy claims survive; a model-claimed BUSY does not.
        //
        // Security review 2026-08-18 (finding 1): the prompt TELLS the model to
        // use constraints[] only for the accompanying text, but that is an
        // instruction, not a boundary. A crafted screenshot with a readable
        // banner ("ONLY AVAILABLE FRIDAY 09:00-09:15") could otherwise induce an
        // AVAILABLE_ONLY sourced from the picture — and while an image-derived
        // window no longer OVERRIDES the calendar, it still BLOCKS every slot
        // outside itself, which is a denial-of-availability path.
        // So: no accompanying text ⇒ no model-supplied constraints at all. The
        // transcription is then the only thing an image can assert.
        if (reading.hasAccompanyingText()) {
            for (RawConstraint raw : primary.constraints()) {
                if (raw.type() != AvailabilityConstraintType.BUSY) {
                    constraints.add(raw);
                }
            }
        } else if (primary.constraints().stream()
                .anyMatch(c -> c.type() != AvailabilityConstraintType.BUSY)) {
            ambiguities.add("Billedet indeholdt tekst om tilgængelighed, som ikke er "
                    + "registreret — skriv den i beskeden, hvis den skal gælde.");
        }

        boolean readNothing = reading.derived().busy().isEmpty()
                && reading.derived().freeDays().isEmpty()
                && constraints.isEmpty();
        if (readNothing) {
            return new Validated(intent, language, primary.timezone(), null, null,
                    List.of(), true, null, List.copyOf(ambiguities),
                    primary.clarifyingQuestion(), REJECT_NO_CONSTRAINTS);
        }
        if (constraints.size() > MAX_CONSTRAINTS) {
            return new Validated(intent, language, primary.timezone(), null, null,
                    List.of(), true, null, List.copyOf(ambiguities),
                    primary.clarifyingQuestion(), REJECT_TOO_MANY_CONSTRAINTS);
        }

        LocalDate earliestAllowed = windowStart.minusDays(WINDOW_SLACK_DAYS);
        LocalDate latestAllowed = windowEnd.plusDays(WINDOW_SLACK_DAYS);
        BigDecimal minConfidence = null;
        for (RawConstraint constraint : constraints) {
            if (!constraint.start().isBefore(constraint.end())) {
                return new Validated(intent, language, primary.timezone(), null, null,
                        List.of(), true, null, List.copyOf(ambiguities),
                        primary.clarifyingQuestion(), REJECT_INVALID_INTERVAL);
            }
            if (constraint.start().toLocalDate().isBefore(earliestAllowed)
                    || constraint.end().toLocalDate().isAfter(latestAllowed)) {
                return new Validated(intent, language, primary.timezone(), null, null,
                        List.of(), true, null, List.copyOf(ambiguities),
                        primary.clarifyingQuestion(), REJECT_OUTSIDE_WINDOW);
            }
            if (minConfidence == null || constraint.confidence().compareTo(minConfidence) < 0) {
                minConfidence = constraint.confidence();
            }
        }

        // The covered range is what was actually TRANSCRIBED, not what the model
        // claimed — the visible-range rule enforced deterministically.
        LocalDate coveredFrom = reading.coveredFrom();
        LocalDate coveredTo = reading.coveredTo();
        if (coveredFrom == null || coveredTo == null) {
            coveredFrom = constraints.stream().map(c -> c.start().toLocalDate())
                    .min(LocalDate::compareTo).orElse(null);
            coveredTo = constraints.stream().map(c -> c.end().toLocalDate())
                    .max(LocalDate::compareTo).orElse(null);
        }
        if (coveredFrom == null || coveredTo == null || coveredFrom.isAfter(coveredTo)
                || coveredFrom.isBefore(earliestAllowed) || coveredTo.isAfter(latestAllowed)) {
            return new Validated(intent, language, primary.timezone(), null, null,
                    List.of(), true, null, List.copyOf(ambiguities),
                    primary.clarifyingQuestion(), REJECT_INVALID_COVERED_RANGE);
        }

        // D9: an image never auto-confirms, whatever the model or the
        // guardrails concluded.
        return new Validated(intent, language, primary.timezone(), coveredFrom, coveredTo,
                List.copyOf(constraints), true, minConfidence,
                List.copyOf(ambiguities), primary.clarifyingQuestion(), null,
                trustCodes(reading));
    }

    private Extraction parse(String json) {
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
                            ? node.path("clarifyingQuestion").asText() : null,
                    parseAxis(node.path("axis")),
                    parseDaysRead(node.path("daysRead")));
        } catch (Exception e) {
            log.warn("Availability extraction returned unusable JSON — treating as UNKNOWN", e);
            return Extraction.unknown();
        }
    }

    /** The v2 axis calibration; null when absent or unusable. */
    private static AvailabilityImageReading.Axis parseAxis(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        LocalTime first = parseTimeOrNull(node.path("firstVisibleTime"));
        LocalTime last = parseTimeOrNull(node.path("lastVisibleTime"));
        String label = node.path("timezoneLabel").isTextual()
                ? node.path("timezoneLabel").asText() : null;
        return new AvailabilityImageReading.Axis(first, last, label);
    }

    /**
     * The v2 per-day transcription. A malformed day is DROPPED rather than
     * poisoning the whole submission (unlike a malformed constraint): the
     * derivation already treats a missing day as "not read", which is the
     * conservative outcome.
     */
    private static List<AvailabilityImageReading.DayRead> parseDaysRead(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AvailabilityImageReading.DayRead> days = new ArrayList<>();
        for (JsonNode d : node) {
            LocalDate date = parseDateOrNull(d.path("date"));
            if (date == null) {
                continue;
            }
            List<AvailabilityImageReading.Block> blocks = new ArrayList<>();
            for (JsonNode b : d.path("blocks")) {
                try {
                    blocks.add(new AvailabilityImageReading.Block(
                            LocalDateTime.parse(b.path("start").asText()),
                            LocalDateTime.parse(b.path("end").asText()),
                            BigDecimal.valueOf(b.path("confidence").asDouble(0))
                                    .setScale(2, RoundingMode.HALF_UP)));
                } catch (Exception badBlock) {
                    // Skip the block, keep the day: a day with one unreadable
                    // block is still better evidence than no day at all, and
                    // derive() records the gap as an ambiguity.
                }
            }
            String verdict = d.path("dayVerdict").asText(AvailabilitySchedulingPrompts.DAY_PARTIAL);
            days.add(new AvailabilityImageReading.DayRead(
                    date,
                    d.path("gridReadable").asBoolean(false),
                    d.path("allDayBandVisible").asBoolean(false),
                    d.path("allDayBandContinuesPastCrop").asBoolean(false),
                    verdict,
                    List.copyOf(blocks)));
        }
        return List.copyOf(days);
    }

    private static LocalTime parseTimeOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return LocalTime.parse(node.asText());
        } catch (Exception e) {
            return null;
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
