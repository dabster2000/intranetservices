package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardGuidance;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardGuidanceCatalog;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2: track-driven defaults and stage-set/scorecard validation
 * ({@code RecruitmentPositionDefaults}) — the PARTNER track inserts
 * INTERVIEW_3, staff owners may trim rounds but never reorder, and the
 * mandatory stages can never be removed.
 */
class RecruitmentPositionDefaultsTest {

    // ---- Defaults -----------------------------------------------------------

    @Test
    void defaultStageSet_practiceTeam_hasFiveStages_withoutThirdInterview() {
        assertEquals(
                List.of("SCREENING", "INTERVIEW_1", "INTERVIEW_2", "OFFER", "HIRED"),
                RecruitmentPositionDefaults.defaultStageSet(RecruitmentHiringTrack.PRACTICE_TEAM));
    }

    @Test
    void defaultStageSet_staffRole_matchesPracticeTeamDefault() {
        assertEquals(
                RecruitmentPositionDefaults.defaultStageSet(RecruitmentHiringTrack.PRACTICE_TEAM),
                RecruitmentPositionDefaults.defaultStageSet(RecruitmentHiringTrack.STAFF_ROLE));
    }

    @Test
    void defaultStageSet_partner_insertsThirdInterview() {
        assertEquals(
                List.of("SCREENING", "INTERVIEW_1", "INTERVIEW_2", "INTERVIEW_3", "OFFER", "HIRED"),
                RecruitmentPositionDefaults.defaultStageSet(RecruitmentHiringTrack.PARTNER));
    }

    @Test
    void defaultScorecard_isTheStandardSixSubjectFrameworkInInterviewOrder() {
        List<ScorecardAttribute> template = RecruitmentPositionDefaults.defaultScorecardTemplate();
        assertEquals(
                List.of("WHY_CONSULTING", "CULTURE", "SELF_LEADERSHIP",
                        "UNCERTAINTY", "FAGLIGHED", "COMMERCIAL_DRIVE"),
                template.stream().map(ScorecardAttribute::code).toList());
        assertTrue(template.stream().noneMatch(a -> a.label() == null || a.label().isBlank()));
    }

    /**
     * The subjects interviewers are asked to score and the subjects they are
     * coached on are the same list — a default subject with no catalog entry
     * would render a scorecard row with no help text on any surface.
     */
    @Test
    void defaultScorecard_everySubjectResolvesGuidance() {
        for (ScorecardAttribute attribute : RecruitmentPositionDefaults.defaultScorecardTemplate()) {
            ScorecardGuidance guidance = ScorecardGuidanceCatalog.forCode(attribute.code())
                    .orElseThrow(() -> new AssertionError(
                            "No guidance for default subject " + attribute.code()));
            assertEquals(attribute.label(), guidance.label());
            assertFalse(guidance.whatYouAreScoring().isBlank());
            assertEquals(ScorecardGuidance.ANCHOR_COUNT, guidance.anchors().size());
            assertFalse(guidance.probes().isEmpty());
        }
    }

    /**
     * Positions snapshotted before the framework moved from four subjects to
     * six still score on {@code CULTURE_FIT}; their in-flight interviews must
     * keep resolving help text.
     */
    @Test
    void guidanceCatalog_stillCoachesTheRetiredCultureFitCode() {
        ScorecardGuidance legacy = ScorecardGuidanceCatalog
                .forCode(ScorecardGuidanceCatalog.CULTURE_FIT_LEGACY_CODE)
                .orElseThrow();
        assertEquals("Culture fit", legacy.label());
        assertEquals(ScorecardGuidance.ANCHOR_COUNT, legacy.anchors().size());
        // Retired: coachable, but never offered when building a new template.
        assertTrue(ScorecardGuidanceCatalog.standard().stream()
                .noneMatch(g -> g.code().equals(ScorecardGuidanceCatalog.CULTURE_FIT_LEGACY_CODE)));
    }

    @Test
    void validateScorecardTemplate_rejectsMoreSubjectsThanTheCap() {
        List<ScorecardAttribute> tooMany = java.util.stream.IntStream
                .rangeClosed(0, RecruitmentPositionDefaults.MAX_SCORECARD_ATTRIBUTES)
                .mapToObj(i -> new ScorecardAttribute("SUBJECT_" + i, "Subject " + i))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateScorecardTemplate(tooMany));
    }

    @Test
    void validateScorecardTemplate_rejectsOversizedCustomHelpText() {
        String tooLong = "x".repeat(ScorecardAttribute.HELP_TEXT_MAX_LENGTH + 1);
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateScorecardTemplate(
                        List.of(new ScorecardAttribute("CUSTOM", "Custom", tooLong))));
    }

    @Test
    void validateScorecardTemplate_acceptsCustomSubjectWithHelpText() {
        assertDoesNotThrow(() -> RecruitmentPositionDefaults.validateScorecardTemplate(
                List.of(new ScorecardAttribute("BOOKKEEPING", "Bookkeeping accuracy",
                        "Can they close a month without surprises?"))));
    }

    // ---- Stage-set validation ------------------------------------------------

    @Test
    void validateStageSet_acceptsTrimmedStaffFlow() {
        assertDoesNotThrow(() -> RecruitmentPositionDefaults.validateStageSet(
                List.of("SCREENING", "INTERVIEW_1", "OFFER", "HIRED")));
    }

    @Test
    void validateStageSet_rejectsEmptyAndNull() {
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateStageSet(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateStageSet(null));
    }

    @Test
    void validateStageSet_rejectsUnknownCode() {
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateStageSet(
                        List.of("SCREENING", "COFFEE_CHAT", "OFFER", "HIRED")));
    }

    @Test
    void validateStageSet_rejectsOutOfOrder() {
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateStageSet(
                        List.of("SCREENING", "INTERVIEW_2", "INTERVIEW_1", "OFFER", "HIRED")));
    }

    @Test
    void validateStageSet_rejectsDuplicates() {
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateStageSet(
                        List.of("SCREENING", "SCREENING", "OFFER", "HIRED")));
    }

    @Test
    void validateStageSet_rejectsMissingMandatoryStages() {
        // No OFFER
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateStageSet(
                        List.of("SCREENING", "INTERVIEW_1", "HIRED")));
        // No SCREENING
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateStageSet(
                        List.of("INTERVIEW_1", "OFFER", "HIRED")));
        // No HIRED
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateStageSet(
                        List.of("SCREENING", "OFFER")));
    }

    // ---- Scorecard validation ---------------------------------------------------

    @Test
    void validateScorecard_rejectsBlankCodesLabelsAndDuplicates() {
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateScorecardTemplate(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateScorecardTemplate(
                        List.of(new ScorecardAttribute(" ", "Label"))));
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateScorecardTemplate(
                        List.of(new ScorecardAttribute("CODE", " "))));
        assertThrows(IllegalArgumentException.class,
                () -> RecruitmentPositionDefaults.validateScorecardTemplate(
                        List.of(new ScorecardAttribute("CODE", "A"),
                                new ScorecardAttribute("CODE", "B"))));
        assertDoesNotThrow(() -> RecruitmentPositionDefaults.validateScorecardTemplate(
                RecruitmentPositionDefaults.defaultScorecardTemplate()));
    }
}
