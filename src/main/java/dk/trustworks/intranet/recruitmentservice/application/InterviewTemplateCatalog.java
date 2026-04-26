package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantRole;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Static catalog of default Interview templates keyed by ({@link HiringCategory},
 * {@link InterviewRoundType}). Provides default duration, focus areas, suggested
 * questions, scorecard weight hints, and required scorer roles for the three
 * canonical round types (FIRST, CASE_OR_TECH, FINAL).
 *
 * <p>SPECIAL rounds are ad-hoc and not covered by the catalog (returns null).
 */
@ApplicationScoped
public class InterviewTemplateCatalog {

    public record TemplateKey(HiringCategory category, InterviewRoundType roundType) {}

    public record InterviewTemplate(
        int defaultDurationMinutes,
        List<String> suggestedFocusAreas,
        List<String> defaultQuestions,
        Map<String, BigDecimal> scorecardWeightHints,
        List<ParticipantRole> defaultRequiredScorerRoles
    ) {}

    private static final Map<TemplateKey, InterviewTemplate> CATALOG;

    static {
        // Defaults — extend as new HiringCategory values are introduced.
        // Weight keys must match Scorecard column names.

        InterviewTemplate phoneScreen = new InterviewTemplate(
            30,
            List.of("Motivation", "Career trajectory", "Communication"),
            List.of(
                "Why are you considering Trustworks now?",
                "Walk me through the role progression you're looking for.",
                "Describe a recent project you're proud of."
            ),
            Map.of(
                "consultingCommunication", new BigDecimal("0.40"),
                "cultureValueFit", new BigDecimal("0.30"),
                "careerLevelFit", new BigDecimal("0.30")
            ),
            List.of(ParticipantRole.LEAD_INTERVIEWER)
        );

        InterviewTemplate techDirectHire = new InterviewTemplate(
            90,
            List.of("Practice depth", "System design", "Code review craft"),
            List.of(
                "Walk through a system you designed end-to-end.",
                "What's your debugging methodology when production is broken?",
                "How do you balance refactoring vs delivering?"
            ),
            Map.of(
                "practiceSkillFit", new BigDecimal("0.40"),
                "deliveryTrackPotential", new BigDecimal("0.30"),
                "consultingCommunication", new BigDecimal("0.30")
            ),
            List.of(ParticipantRole.LEAD_INTERVIEWER, ParticipantRole.SCORER)
        );

        InterviewTemplate finalInterview = new InterviewTemplate(
            60,
            List.of("Client-facing maturity", "Culture fit", "Closing decision"),
            List.of(
                "Tell me about a difficult client situation and how you handled it.",
                "Where do you see yourself in 3 years at Trustworks?",
                "What concerns do you have about joining us?"
            ),
            Map.of(
                "clientFacingMaturity", new BigDecimal("0.40"),
                "cultureValueFit", new BigDecimal("0.40"),
                "deliveryTrackPotential", new BigDecimal("0.20")
            ),
            List.of(ParticipantRole.LEAD_INTERVIEWER, ParticipantRole.SCORER)
        );

        // Cover every HiringCategory × {FIRST, CASE_OR_TECH, FINAL} combination.
        // For v1 we use the same three templates across all categories;
        // category-specific variants will be introduced as the slice matures.
        var first = phoneScreen;
        var caseTech = techDirectHire;
        var fin = finalInterview;

        // 5 hiring categories × 3 round types = 15 entries — Map.ofEntries required.
        CATALOG = Map.ofEntries(
            Map.entry(new TemplateKey(HiringCategory.PRACTICE_CONSULTANT,    InterviewRoundType.FIRST),         first),
            Map.entry(new TemplateKey(HiringCategory.PRACTICE_CONSULTANT,    InterviewRoundType.CASE_OR_TECH),  caseTech),
            Map.entry(new TemplateKey(HiringCategory.PRACTICE_CONSULTANT,    InterviewRoundType.FINAL),         fin),
            Map.entry(new TemplateKey(HiringCategory.JUNIOR_CONSULTANT,      InterviewRoundType.FIRST),         first),
            Map.entry(new TemplateKey(HiringCategory.JUNIOR_CONSULTANT,      InterviewRoundType.CASE_OR_TECH),  caseTech),
            Map.entry(new TemplateKey(HiringCategory.JUNIOR_CONSULTANT,      InterviewRoundType.FINAL),         fin),
            Map.entry(new TemplateKey(HiringCategory.STAFF,                  InterviewRoundType.FIRST),         first),
            Map.entry(new TemplateKey(HiringCategory.STAFF,                  InterviewRoundType.CASE_OR_TECH),  caseTech),
            Map.entry(new TemplateKey(HiringCategory.STAFF,                  InterviewRoundType.FINAL),         fin),
            Map.entry(new TemplateKey(HiringCategory.PARTNER_OR_LEADERSHIP,  InterviewRoundType.FIRST),         first),
            Map.entry(new TemplateKey(HiringCategory.PARTNER_OR_LEADERSHIP,  InterviewRoundType.CASE_OR_TECH),  caseTech),
            Map.entry(new TemplateKey(HiringCategory.PARTNER_OR_LEADERSHIP,  InterviewRoundType.FINAL),         fin),
            Map.entry(new TemplateKey(HiringCategory.SPECIAL_CASE,           InterviewRoundType.FIRST),         first),
            Map.entry(new TemplateKey(HiringCategory.SPECIAL_CASE,           InterviewRoundType.CASE_OR_TECH),  caseTech),
            Map.entry(new TemplateKey(HiringCategory.SPECIAL_CASE,           InterviewRoundType.FINAL),         fin)
        );
    }

    public InterviewTemplate templateFor(HiringCategory category, InterviewRoundType roundType) {
        if (roundType == InterviewRoundType.SPECIAL) return null;
        return CATALOG.get(new TemplateKey(category, roundType));
    }
}
