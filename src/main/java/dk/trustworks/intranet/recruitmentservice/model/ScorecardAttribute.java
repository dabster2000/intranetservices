package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One attribute of a position's scorecard template (ATS spec §4.1):
 * a stable {@code code} scorecards key their 1–4 scores on (P11), plus the
 * human label interviewers see. The default template encodes the existing
 * Trustworks interview framework (spec §2.2) — see
 * {@code RecruitmentPositionDefaults#defaultScorecardTemplate()}.
 * <p>
 * Coaching text for the standard subjects is NOT stored here: it lives in
 * {@link ScorecardGuidanceCatalog}, resolved by {@code code} at render time so
 * wording improvements reach positions created long before them. {@code
 * helpText} exists only for custom subjects a hiring owner adds in the
 * position editor, which by definition have no catalog entry.
 *
 * @param code     stable machine code, e.g. {@code WHY_CONSULTING} — never
 *                 rename once scorecards reference it
 * @param label    display label, e.g. {@code "Why consulting"}
 * @param helpText optional coaching for a custom subject; {@code null} for
 *                 standard subjects, which resolve richer guidance by code
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScorecardAttribute(String code, String label, String helpText) {

    /** Cap on custom help text — long enough for a definition, short enough to read on a phone. */
    public static final int HELP_TEXT_MAX_LENGTH = 600;

    @JsonCreator
    public ScorecardAttribute(@JsonProperty("code") String code,
                              @JsonProperty("label") String label,
                              @JsonProperty("helpText") String helpText) {
        this.code = code;
        this.label = label;
        this.helpText = helpText == null || helpText.isBlank() ? null : helpText.trim();
    }

    /** Convenience for the common case — a standard subject coached by the catalog. */
    public ScorecardAttribute(String code, String label) {
        this(code, label, null);
    }
}
