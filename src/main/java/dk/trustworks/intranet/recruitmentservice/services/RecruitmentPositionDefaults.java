package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Track-driven defaults and stage-set validation for recruitment positions
 * (ATS spec §4.1 design notes).
 * <p>
 * Defaults are <em>snapshotted</em> onto the position at create time — a
 * later change to these constants must never rewrite in-flight positions.
 * <ul>
 *   <li>Default stage set: {@code SCREENING → INTERVIEW_1 → INTERVIEW_2 →
 *       OFFER → HIRED}; the partner track inserts {@code INTERVIEW_3}.</li>
 *   <li>Default scorecard: the standard 4-attribute Trustworks interview
 *       framework (spec §2.2): why consulting, commercial drive, handling
 *       uncertainty, culture fit.</li>
 * </ul>
 */
public final class RecruitmentPositionDefaults {

    private RecruitmentPositionDefaults() {
    }

    /** The standard 4-attribute interview framework (stable codes — P11 scorecards key on them). */
    public static final List<ScorecardAttribute> STANDARD_SCORECARD = List.of(
            new ScorecardAttribute("WHY_CONSULTING", "Why consulting"),
            new ScorecardAttribute("COMMERCIAL_DRIVE", "Commercial drive"),
            new ScorecardAttribute("UNCERTAINTY", "Handling uncertainty"),
            new ScorecardAttribute("CULTURE_FIT", "Culture fit"));

    /** @return the default ordered stage codes for the given track (mutable copy). */
    public static List<String> defaultStageSet(RecruitmentHiringTrack track) {
        List<String> stages = new ArrayList<>(List.of(
                RecruitmentStage.SCREENING.name(),
                RecruitmentStage.INTERVIEW_1.name(),
                RecruitmentStage.INTERVIEW_2.name()));
        if (track == RecruitmentHiringTrack.PARTNER) {
            stages.add(RecruitmentStage.INTERVIEW_3.name());
        }
        stages.add(RecruitmentStage.OFFER.name());
        stages.add(RecruitmentStage.HIRED.name());
        return stages;
    }

    /** @return the standard 4-attribute scorecard template (mutable copy). */
    public static List<ScorecardAttribute> defaultScorecardTemplate() {
        return new ArrayList<>(STANDARD_SCORECARD);
    }

    /**
     * Validate a per-position stage-set override: every code must be a known
     * {@link RecruitmentStage}, appear at most once, respect the canonical
     * pipeline order, and the set must contain the mandatory stages
     * (SCREENING, OFFER, HIRED). Staff-role owners may trim rounds — e.g.
     * {@code [SCREENING, INTERVIEW_1, OFFER, HIRED]} — but never reorder.
     *
     * @throws IllegalArgumentException with a user-readable message when invalid
     */
    public static void validateStageSet(List<String> stageSet) {
        if (stageSet == null || stageSet.isEmpty()) {
            throw new IllegalArgumentException("Stage set must not be empty");
        }
        Set<String> seen = new HashSet<>();
        int lastOrdinal = -1;
        for (String code : stageSet) {
            RecruitmentStage stage;
            try {
                stage = RecruitmentStage.valueOf(code);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("Unknown stage code: " + code);
            }
            if (!seen.add(code)) {
                throw new IllegalArgumentException("Duplicate stage code: " + code);
            }
            if (stage.ordinal() <= lastOrdinal) {
                throw new IllegalArgumentException(
                        "Stage set must follow the canonical pipeline order; '" + code + "' is out of order");
            }
            lastOrdinal = stage.ordinal();
        }
        for (RecruitmentStage mandatory : RecruitmentStage.MANDATORY) {
            if (!seen.contains(mandatory.name())) {
                throw new IllegalArgumentException(
                        "Stage set must contain " + mandatory.name());
            }
        }
    }

    /**
     * Validate a per-position scorecard-template override: non-empty, codes
     * non-blank and unique, labels non-blank.
     *
     * @throws IllegalArgumentException with a user-readable message when invalid
     */
    public static void validateScorecardTemplate(List<ScorecardAttribute> template) {
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException("Scorecard template must not be empty");
        }
        Set<String> codes = new HashSet<>();
        for (ScorecardAttribute attribute : template) {
            if (attribute == null || attribute.code() == null || attribute.code().isBlank()) {
                throw new IllegalArgumentException("Scorecard attribute code must not be blank");
            }
            if (attribute.label() == null || attribute.label().isBlank()) {
                throw new IllegalArgumentException(
                        "Scorecard attribute '" + attribute.code() + "' must have a label");
            }
            if (!codes.add(attribute.code())) {
                throw new IllegalArgumentException(
                        "Duplicate scorecard attribute code: " + attribute.code());
            }
        }
    }
}
