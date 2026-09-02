package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The template-key fall-through the candidate mailer resolves a rejection
 * or a pooling with (2026-09-02).
 * <p>
 * The load-bearing property is the LAST rung: it is what this trigger sent
 * before the chain existed, so a pipeline that configures no specific
 * letters behaves exactly as it always did. Every test here that asserts
 * the tail is guarding that promise, not the ordering.
 */
class RecruitmentRejectionKeyChainTest {

    @Test
    void screeningWithAReasonTriesStageThenReasonThenGeneric() {
        assertEquals(List.of(
                        "REJECTION_EXPERIENCE_LEVEL_SCREENING",
                        "REJECTION_EXPERIENCE_LEVEL",
                        "REJECTION_SCREENING"),
                RecruitmentEmailService.rejectionKeyChain(
                        RecruitmentRejectionReason.EXPERIENCE_LEVEL.name(),
                        RecruitmentStage.SCREENING.name()));
    }

    @Test
    void everyLaterStageSharesThePostInterviewBucket() {
        // The bucket is the same SCREENING-vs-later split the two generic
        // keys have always used — a second stage vocabulary would mean TA
        // writing four near-identical letters per reason.
        for (RecruitmentStage stage : List.of(RecruitmentStage.INTERVIEW_1,
                RecruitmentStage.INTERVIEW_2, RecruitmentStage.INTERVIEW_3,
                RecruitmentStage.OFFER)) {
            assertEquals(List.of(
                            "REJECTION_CULTURE_FIT_POST_INTERVIEW",
                            "REJECTION_CULTURE_FIT",
                            "REJECTION_POST_INTERVIEW"),
                    RecruitmentEmailService.rejectionKeyChain(
                            RecruitmentRejectionReason.CULTURE_FIT.name(), stage.name()),
                    "stage " + stage);
        }
    }

    @Test
    void withoutAReasonTheChainIsExactlyThePreviousBehaviour() {
        assertEquals(List.of("REJECTION_SCREENING"),
                RecruitmentEmailService.rejectionKeyChain(null, "SCREENING"));
        assertEquals(List.of("REJECTION_POST_INTERVIEW"),
                RecruitmentEmailService.rejectionKeyChain("  ", "INTERVIEW_1"));
        assertEquals(List.of("REJECTION_POST_INTERVIEW"),
                RecruitmentEmailService.rejectionKeyChain(null, null));
    }

    @Test
    void everyReasonCodeProducesKeysTheTemplateTableCanHold() {
        // template_key is VARCHAR(60) with a 2-60 character pattern; a
        // reason code long enough to overflow it would fail at template
        // creation, i.e. only once TA tried to write the letter.
        for (RecruitmentRejectionReason reason : RecruitmentRejectionReason.values()) {
            for (String key : RecruitmentEmailService.rejectionKeyChain(
                    reason.name(), RecruitmentStage.INTERVIEW_1.name())) {
                assertTrue(key.length() <= 60, key + " is " + key.length() + " characters");
                assertTrue(key.matches("[A-Z][A-Z0-9_]{1,59}"), key + " is not a legal key");
            }
        }
    }

    @Test
    void everyRungOfEveryChainIsRecognisedAsATrigger() {
        // Otherwise the settings dialog would offer TA a purpose it then
        // describes as manual-send-only.
        for (RecruitmentRejectionReason reason : RecruitmentRejectionReason.values()) {
            for (String stage : List.of("SCREENING", "INTERVIEW_2")) {
                for (String key : RecruitmentEmailService.rejectionKeyChain(
                        reason.name(), stage)) {
                    assertTrue(RecruitmentEmailService.isTriggerKey(key),
                            key + " must classify as a trigger");
                }
            }
        }
    }

    @Test
    void poolingTriesTheBucketThenTheGenericLetter() {
        assertEquals(List.of("POOLED_SILVER_MEDALIST", "POOLED"),
                RecruitmentEmailService.pooledKeyChain(
                        CandidatePoolStatus.SILVER_MEDALIST.name()));
        assertEquals(List.of("POOLED"),
                RecruitmentEmailService.pooledKeyChain(null));
    }

    @Test
    void everyPoolBucketProducesARecognisedTriggerKey() {
        for (CandidatePoolStatus status : CandidatePoolStatus.values()) {
            for (String key : RecruitmentEmailService.pooledKeyChain(status.name())) {
                assertTrue(RecruitmentEmailService.isTriggerKey(key),
                        key + " must classify as a trigger");
                assertTrue(key.matches("[A-Z][A-Z0-9_]{1,59}"), key + " is not a legal key");
            }
        }
    }

    @Test
    void aRejectionKeyNothingCanFireIsNotATrigger() {
        // Prefix-matching would have called these triggers and put a "sent
        // automatically" explainer on rows that never send.
        assertTrue(!RecruitmentEmailService.isTriggerKey("REJECTION_BECAUSE_I_SAID_SO"));
        assertTrue(!RecruitmentEmailService.isTriggerKey("REJECTION_EXPERIENCE_LEVEL_OFFER"));
        assertTrue(!RecruitmentEmailService.isTriggerKey("POOLED_MAYBE"));
    }
}
