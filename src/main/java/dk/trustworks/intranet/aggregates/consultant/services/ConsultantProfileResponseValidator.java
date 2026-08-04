package dk.trustworks.intranet.aggregates.consultant.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hard guard on the model's structured output, and the single place where AI labels are
 * sanitised into the shape the DTO contract promises.
 *
 * <p>This exists because of a concrete production defect: {@code OpenAIService} returns the
 * literal {@code "{}"} on HTTP error, timeout and empty output, and the previous call site
 * parsed that <em>successfully</em> — {@code path("industries")} yields a {@code MissingNode},
 * which Jackson serialises as the 4-character string {@code "null"}. That string is valid JSON,
 * passes MariaDB's JSON column check, and was written back with {@code generated_at} stamped,
 * so the empty profile was served as fresh for a full week. A string comparison is therefore
 * <b>not</b> sufficient: the shape of every field has to be checked.
 *
 * <p>Pure and static by design so it can be unit-tested without a container.
 */
public final class ConsultantProfileResponseValidator {

    /** Generous upper bound; the prompt asks for 160 chars. Anything longer is a runaway answer. */
    static final int MAX_PITCH_CHARS = 400;
    /** Chip labels: the card is 240–420 px wide, so a longer label is never renderable. */
    public static final int MAX_LABEL_CHARS = 32;
    /** The AI list is only a fallback, so it is allowed one more entry than the derived list. */
    public static final int MAX_AI_INDUSTRIES = 3;
    public static final int MAX_SKILLS = 4;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** Diagnostic failure codes — persisted to {@code last_error}, never rendered to users. */
    public static final String CODE_EMPTY_OUTPUT = "empty-output";
    public static final String CODE_UNPARSEABLE = "unparseable";
    public static final String CODE_BAD_PITCH = "bad-pitch";
    public static final String CODE_BAD_INDUSTRIES = "bad-industries";
    public static final String CODE_BAD_SKILLS = "bad-skills";

    private ConsultantProfileResponseValidator() {
    }

    public sealed interface Result permits Accepted, Rejected {
    }

    /** Sanitised, ready to persist. All fields are non-null; the lists may be empty. */
    public record Accepted(String pitch, List<String> industries, List<String> topSkills) implements Result {
        public Accepted {
            industries = industries == null ? List.of() : List.copyOf(industries);
            topSkills = topSkills == null ? List.of() : List.copyOf(topSkills);
        }
    }

    /** Nothing may be written on this outcome except the failure bookkeeping. */
    public record Rejected(String code) implements Result {
    }

    /**
     * Validates one raw model response.
     *
     * @param json   the raw output text (may be null, blank, or the literal {@code "{}"})
     * @param mapper the CDI {@code ObjectMapper}
     * @return {@link Accepted} with sanitised content, or {@link Rejected} with a failure code
     */
    public static Result validate(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return new Rejected(CODE_EMPTY_OUTPUT);
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            return new Rejected(CODE_UNPARSEABLE);
        }
        if (root == null || !root.isObject()) {
            return new Rejected(CODE_UNPARSEABLE);
        }

        String pitch = sanitizeText(root.path("pitch").asText(""));
        if (pitch == null || pitch.length() > MAX_PITCH_CHARS) {
            return new Rejected(CODE_BAD_PITCH);
        }
        // Shape checks, not string checks: a MissingNode/NullNode must not slip through.
        if (!root.path("industries").isArray()) {
            return new Rejected(CODE_BAD_INDUSTRIES);
        }
        if (!root.path("topSkills").isArray()) {
            return new Rejected(CODE_BAD_SKILLS);
        }

        List<String> industries = sanitizeLabels(stringList(root.path("industries")), MAX_AI_INDUSTRIES);
        List<String> topSkills = dropLabelsMatching(
                sanitizeLabels(stringList(root.path("topSkills")), MAX_SKILLS), industries);
        return new Accepted(pitch, industries, topSkills);
    }

    /**
     * Trim, strip control characters, collapse whitespace, drop blanks, cap at
     * {@value #MAX_LABEL_CHARS} characters, dedupe case-insensitively, preserve order,
     * then keep at most {@code maxItems}.
     */
    public static List<String> sanitizeLabels(List<String> raw, int maxItems) {
        if (raw == null || raw.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>(Math.min(raw.size(), maxItems));
        for (String candidate : raw) {
            String cleaned = sanitizeText(candidate);
            if (cleaned == null) {
                continue;
            }
            if (cleaned.length() > MAX_LABEL_CHARS) {
                cleaned = cleaned.substring(0, MAX_LABEL_CHARS).trim();
                if (cleaned.isEmpty()) {
                    continue;
                }
            }
            if (!seen.add(cleaned.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(cleaned);
            if (out.size() == maxItems) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /**
     * Drops labels that case-insensitively duplicate one of {@code exclusions}. Used so a value
     * like "Energy" cannot be rendered twice on the card in two different chip colours.
     */
    public static List<String> dropLabelsMatching(List<String> labels, List<String> exclusions) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        if (exclusions == null || exclusions.isEmpty()) {
            return List.copyOf(labels);
        }
        Set<String> blocked = new LinkedHashSet<>();
        for (String exclusion : exclusions) {
            blocked.add(exclusion.toLowerCase(Locale.ROOT));
        }
        return labels.stream()
                .filter(label -> !blocked.contains(label.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String sanitizeText(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = WHITESPACE_RUN
                .matcher(CONTROL_CHARS.matcher(value).replaceAll(" "))
                .replaceAll(" ")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out;
    }
}
