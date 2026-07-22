package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void defaultScorecard_isTheStandardFourAttributeFramework() {
        List<ScorecardAttribute> template = RecruitmentPositionDefaults.defaultScorecardTemplate();
        assertEquals(
                List.of("WHY_CONSULTING", "COMMERCIAL_DRIVE", "UNCERTAINTY", "CULTURE_FIT"),
                template.stream().map(ScorecardAttribute::code).toList());
        assertTrue(template.stream().noneMatch(a -> a.label() == null || a.label().isBlank()));
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
