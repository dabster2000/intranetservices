package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Totality guard for {@link RecruitmentDanishLabels}.
 *
 * <p>The digest used to hand bare enum codes to the model, which then
 * leaked them into Danish prose ("fra INTERVIEW_1 til INTERVIEW_2"). The
 * fix is a translation table — and a translation table is only a fix while
 * it stays complete. Adding an enum constant without a Danish word must
 * break the build here rather than ship a SCREAMING_SNAKE code to
 * Slack.</p>
 *
 * <p>DB-free by design: runs in the fast tier that gates deploys.</p>
 */
class RecruitmentDanishLabelsTest {

    /** The four vocabularies that reach a human through the digest. */
    private static List<String> allCodes() {
        List<String> codes = new ArrayList<>();
        for (RecruitmentStage v : RecruitmentStage.values()) {
            codes.add(v.name());
        }
        for (CandidateSource v : CandidateSource.values()) {
            codes.add(v.name());
        }
        for (RecruitmentHiringTrack v : RecruitmentHiringTrack.values()) {
            codes.add(v.name());
        }
        for (RecruitmentApplicationTerminal v : RecruitmentApplicationTerminal.values()) {
            codes.add(v.name());
        }
        return codes;
    }

    @Test
    @DisplayName("every enum constant that can reach Slack has an explicit Danish label")
    void everyCodeIsLabelled() {
        List<String> missing = allCodes().stream()
                .filter(code -> !RecruitmentDanishLabels.hasLabel(code))
                .toList();
        assertTrue(missing.isEmpty(),
                "No Danish label for " + missing + " — add it to RecruitmentDanishLabels, "
                        + "otherwise the raw code is what recruiters read in Slack.");
    }

    @Test
    @DisplayName("no label is just the code back again")
    void labelsAreActuallyTranslated() {
        for (String code : allCodes()) {
            String label = RecruitmentDanishLabels.label(code);
            assertNotNull(label, code);
            // A label may legitimately match the code as a word — "Screening"
            // is the Danish for SCREENING. What must never survive is the
            // code SHAPE: underscores or all-caps.
            assertFalse(label.contains("_"),
                    "Label for " + code + " still looks like a code: " + label);
            assertFalse(label.equals(label.toUpperCase(java.util.Locale.ROOT))
                            && label.length() > 3,
                    "Label for " + code + " is still SCREAMING_SNAKE: " + label);
            assertFalse(code.contains("_") && code.equalsIgnoreCase(label),
                    "Multi-word code " + code + " was never translated: " + label);
        }
    }

    @Test
    @DisplayName("the codes that actually leaked into production prose translate correctly")
    void theRegressionCases() {
        assertEquals("1. samtale", RecruitmentDanishLabels.label("INTERVIEW_1"));
        assertEquals("2. samtale", RecruitmentDanishLabels.label("INTERVIEW_2"));
        // PRACTICE_TEAM is a hiring track. The old prompt listed tracks and
        // sources in the same shape, so the model called it a "kilde".
        assertEquals("Praksisteam", RecruitmentDanishLabels.label("PRACTICE_TEAM"));
        assertEquals("LinkedIn (opsøgt)", RecruitmentDanishLabels.label("LINKEDIN_SEARCH"));
        assertEquals("Partnerhenvisning", RecruitmentDanishLabels.label("PARTNER_REFERRAL"));
    }

    @Test
    @DisplayName("the '' projection sentinel and unknown codes never surface raw")
    void unknownCodesDegradeGracefully() {
        assertEquals(RecruitmentDanishLabels.UNKNOWN, RecruitmentDanishLabels.label(""));
        assertEquals(RecruitmentDanishLabels.UNKNOWN, RecruitmentDanishLabels.label(null));
        assertEquals(RecruitmentDanishLabels.UNKNOWN, RecruitmentDanishLabels.label("   "));
        // A value written by a newer image than this one: humanised, not raw.
        assertEquals("Some future stage", RecruitmentDanishLabels.label("SOME_FUTURE_STAGE"));
    }

    @Test
    @DisplayName("the prompt form keeps the code visible alongside the Danish word")
    void labelWithCodeCarriesBoth() {
        assertEquals("1. samtale (INTERVIEW_1)",
                RecruitmentDanishLabels.labelWithCode("INTERVIEW_1"));
        assertEquals(RecruitmentDanishLabels.UNKNOWN,
                RecruitmentDanishLabels.labelWithCode(""));
    }
}
