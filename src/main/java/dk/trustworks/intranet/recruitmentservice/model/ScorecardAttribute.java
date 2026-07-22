package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One attribute of a position's scorecard template (ATS spec §4.1):
 * a stable {@code code} scorecards key their 1–4 scores on (P11), plus the
 * human label interviewers see. The default template encodes the existing
 * Trustworks interview framework (spec §2.2) — see
 * {@code RecruitmentPositionDefaults#defaultScorecardTemplate()}.
 *
 * @param code  stable machine code, e.g. {@code WHY_CONSULTING} — never
 *              rename once scorecards reference it
 * @param label display label, e.g. {@code "Why consulting"}
 */
public record ScorecardAttribute(String code, String label) {

    @JsonCreator
    public ScorecardAttribute(@JsonProperty("code") String code,
                              @JsonProperty("label") String label) {
        this.code = code;
        this.label = label;
    }
}
