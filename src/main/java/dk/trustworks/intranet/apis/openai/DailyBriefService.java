package dk.trustworks.intranet.apis.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Composes a short, warm, personal morning note for an employee's dashboard.
 *
 * <p>Orchestration + prompt building only: it sanitizes and caps the caller-supplied inputs, frames
 * them as a compact JSON payload (with the current date and a per-call variation seed so successive
 * days never read templated), and delegates the actual generation to
 * {@link OpenAIService#generatePlainText}. The generated note is returned verbatim; on any upstream
 * failure it falls back to a graceful localised greeting rather than surfacing an error, because the
 * note is cosmetic dashboard chrome, not a transactional result.
 */
@JBossLog
@ApplicationScoped
public class DailyBriefService {

    /** At most one to-do is woven in, but a few are sent so the model can pick the most important. */
    static final int MAX_TODOS = 6;
    static final int MAX_EVENTS = 5;
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_ITEM_LENGTH = 200;
    /** 2-3 sentences fit comfortably; a non-reasoning model won't burn this budget on thinking. */
    private static final int MAX_OUTPUT_TOKENS = 120;
    private static final double TEMPERATURE = 0.8d;
    private static final DateTimeFormatter EN_DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DA_DATE = DateTimeFormatter.ofPattern("EEEE 'd.' d. MMMM yyyy", Locale.of("da"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    OpenAIService openAIService;

    /**
     * Dedicated model for the daily brief. A gpt-4o-family model is required because the note is
     * generated with a temperature for day-to-day warmth/variation, which the gpt-5 family rejects
     * with HTTP 400. Override per env with OPENAI_DAILY_BRIEF_MODEL.
     */
    @ConfigProperty(name = "openai.daily-brief-model", defaultValue = "gpt-4o-mini")
    String dailyBriefModel;

    /** Immutable, resolved locale for the note. */
    enum Language {
        EN("English"), DA("Danish (dansk)");

        final String label;

        Language(String label) {
            this.label = label;
        }

        static Language of(String raw) {
            return raw != null && raw.trim().equalsIgnoreCase("da") ? DA : EN;
        }
    }

    public String generate(DailyBriefRequest request) {
        Language language = Language.of(request.locale());
        String firstName = sanitize(request.name(), MAX_NAME_LENGTH);

        LocalDate today = LocalDate.now();
        int variationSeed = new Random().nextInt(1_000);
        String userMsg = buildUserPrompt(request, firstName, language, today, variationSeed);

        log.debugf("POST /openai/daily-brief: model=%s lang=%s todos=%d events=%d",
                dailyBriefModel, language, sizeOf(request.todoLabels()), sizeOf(request.upcomingEvents()));

        String note = openAIService.generatePlainText(
                SYSTEM_PROMPT, userMsg, dailyBriefModel, MAX_OUTPUT_TOKENS, TEMPERATURE, false);

        if (note == null || note.isBlank()) {
            log.warnf("[DailyBrief] Empty model output (model=%s) — using fallback greeting", dailyBriefModel);
            return fallback(firstName, language);
        }
        return stripWrappingQuotes(note.trim());
    }

    // --- Prompt building (package-private for unit tests) ---

    String buildUserPrompt(DailyBriefRequest request, String firstName, Language language,
                           LocalDate today, int variationSeed) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("date", formatDate(today, language));
        root.put("variationSeed", variationSeed);
        root.put("language", language.label);
        root.put("name", firstName);

        String role = sanitize(request.roleContext(), MAX_ITEM_LENGTH);
        if (!role.isEmpty()) {
            root.put("roleContext", role);
        }

        ArrayNode todos = capAndSanitize(request.todoLabels(), MAX_TODOS);
        if (!todos.isEmpty()) {
            root.set("todos", todos);
        }

        ArrayNode events = capAndSanitize(request.upcomingEvents(), MAX_EVENTS);
        if (!events.isEmpty()) {
            root.set("upcomingEvents", events);
        }

        String utilization = sanitize(request.utilizationNote(), MAX_ITEM_LENGTH);
        if (!utilization.isEmpty()) {
            root.put("utilizationNote", utilization);
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // Fields are already sanitized primitives, so this is effectively unreachable.
            log.error("[DailyBrief] Failed to serialize prompt payload", e);
            return "{\"name\":\"" + firstName + "\",\"language\":\"" + language.label + "\"}";
        }
    }

    private ArrayNode capAndSanitize(List<String> values, int max) {
        ArrayNode array = MAPPER.createArrayNode();
        if (values == null) {
            return array;
        }
        int added = 0;
        for (String raw : values) {
            if (added >= max) {
                break;
            }
            String clean = sanitize(raw, MAX_ITEM_LENGTH);
            if (!clean.isEmpty()) {
                array.add(clean);
                added++;
            }
        }
        return array;
    }

    private String formatDate(LocalDate date, Language language) {
        if (language == Language.DA) {
            String formatted = date.format(DA_DATE);
            // Capitalise the weekday for a natural Danish sentence lead-in.
            return formatted.isEmpty() ? formatted
                    : Character.toUpperCase(formatted.charAt(0)) + formatted.substring(1);
        }
        return date.format(EN_DATE);
    }

    private String fallback(String firstName, Language language) {
        String name = firstName.isEmpty() ? (language == Language.DA ? "der" : "there") : firstName;
        return language == Language.DA
                ? "Godmorgen, " + name + ". Håber du får en god dag."
                : "Good morning, " + name + ". Hope you have a good day.";
    }

    // --- Helpers ---

    /**
     * Strip HTML tags and control characters (newlines included) and cap length, defusing prompt
     * injection from user-controlled strings. Mirrors {@code AccountManagerBriefService#sanitize}.
     */
    static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String stripped = raw
                .replaceAll("<[^>]*>", "")
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s{2,}", " ")
                .strip();
        return stripped.length() > maxLength ? stripped.substring(0, maxLength).strip() : stripped;
    }

    /** Remove a single pair of wrapping quotes the model sometimes adds despite instructions. */
    static String stripWrappingQuotes(String text) {
        if (text.length() >= 2
                && (text.charAt(0) == '"' || text.charAt(0) == '“')
                && (text.charAt(text.length() - 1) == '"' || text.charAt(text.length() - 1) == '”')) {
            return text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    private static int sizeOf(List<String> list) {
        return list == null ? 0 : list.size();
    }

    private static final String SYSTEM_PROMPT = """
            You are writing a short, warm, personal good-morning note for an employee's dashboard at \
            Trustworks — a Danish IT consultancy with a strong solidarity culture: "One Trustworks", \
            active knowledge sharing, and a genuine belief in talent and passion. The reader opens \
            this note first thing in the morning.

            Write 2-3 sentences. Follow every rule:
            - Address the person by their first name. Be warm and genuine but professional — never \
              cheesy, never gushing, never over-exclaiming. Use at most one exclamation mark in the \
              whole note, and prefer none. No emojis.
            - If "todos" are present, gently weave in AT MOST the single most important one as a light, \
              encouraging nudge — never a guilt trip, never a scolding. Ignore the rest. If there are \
              no todos, do not invent any or imply the reader is behind.
            - If an entry in "upcomingEvents" is genuinely near, mention it with authentic anticipation. \
              If nothing feels near, leave events out rather than forcing them in.
            - If "utilizationNote" is present, you may acknowledge it briefly and supportively — as \
              shared momentum, never as pressure or a target to hit.
            - Vary the opening, structure, and rhythm from day to day. Use the given "date" and \
              "variationSeed" to pick a fresh angle so the note never reads templated or repetitive.
            - Write the entire note in the language named by "language".
            - Only use facts present in the input. Never invent names, numbers, deadlines, or events.

            Output ONLY the note text itself: no preamble, no salutation label, no surrounding quotes, \
            no markdown, no sign-off.
            """;
}
