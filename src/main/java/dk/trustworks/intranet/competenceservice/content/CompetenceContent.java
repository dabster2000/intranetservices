package dk.trustworks.intranet.competenceservice.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The authored payload shapes stored in {@code competence_content_version.payload_json}.
 *
 * <p>Two payload kinds share one table because their lifecycle, approval record and
 * version semantics are identical — only the shape below differs.
 *
 * <p>Blocks are modelled as one flat record rather than a polymorphic hierarchy. There
 * are exactly seven block types and each uses a small, overlapping subset of the fields;
 * a sealed hierarchy would buy type-safety that {@link CompetenceContentValidator} has to
 * assert at runtime anyway, because the payload arrives as author-supplied JSON. Unknown
 * properties are rejected rather than ignored, so a typo in an imported file surfaces as
 * a validation error instead of silently dropping content.
 */
public final class CompetenceContent {

    /** The seven authorable block types. Anything else is a validation error. */
    public static final List<String> BLOCK_TYPES =
            List.of("heading", "paragraph", "list", "code", "callout", "video", "keypoints");

    /** The four screen roles. Drives eyebrow derivation (spec §9.2). */
    public static final List<String> SCREEN_ROLES = List.of("intro", "content", "video", "summary");

    private CompetenceContent() {
    }

    // -----------------------------------------------------------------------
    // COURSE
    // -----------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CoursePayload(List<Screen> screens) {

        public CoursePayload {
            screens = screens == null ? List.of() : List.copyOf(screens);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Screen(String role, String title, String lede, List<Block> blocks) {

        public Screen {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }

    /**
     * One content block.
     *
     * <p>Field use by type:
     * <ul>
     *   <li>{@code heading}, {@code paragraph}, {@code callout} — {@link #text()}</li>
     *   <li>{@code list} — {@link #items()} plus {@link #ordered()}</li>
     *   <li>{@code keypoints} — {@link #items()}</li>
     *   <li>{@code code} — {@link #code()}, rendered literally, never inline-parsed</li>
     *   <li>{@code video} — {@link #note()}, plus {@link #provider()}/{@link #videoId()}
     *       once an author sets them. The 2026-08 export carries note only, so both are
     *       optional and the id pattern is validated only when present.</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Block(String type,
                        String text,
                        List<String> items,
                        Boolean ordered,
                        String code,
                        String note,
                        String provider,
                        String videoId) {

        public Block {
            items = items == null ? null : List.copyOf(items);
        }
    }

    // -----------------------------------------------------------------------
    // TEST
    // -----------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TestPayload(List<Question> questions) {

        public TestPayload {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }

        public Question byId(String questionId) {
            return questions.stream().filter(q -> q.id().equals(questionId)).findFirst().orElse(null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Question(String id, String text, List<Option> options) {

        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }

        /** The single correct option, or {@code null} for a payload that failed validation. */
        public Option correctOption() {
            return options.stream().filter(Option::correct).findFirst().orElse(null);
        }

        public boolean hasOption(String optionId) {
            return options.stream().anyMatch(o -> o.id().equals(optionId));
        }
    }

    /**
     * One answer option.
     *
     * <p>{@code correct} is stored but never serialised to a learner. The learner DTO is a
     * separate type that has no such field at all — deliberately not a {@code @JsonIgnore}
     * on this record, which is one refactor away from leaking (spec §6.4, §10.2).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Option(String id, String text, boolean correct) {
    }
}
