package dk.trustworks.intranet.recruitmentservice.notifications;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event payload → template-key chain, without the chassis or a database.
 * <p>
 * The rejecter's two overrides are the branches worth pinning: both change
 * what a candidate receives, and both are decided here rather than in the
 * dialog that offered them.
 */
class CandidateMailerKeyResolutionTest {

    private static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void aRejectionWithoutOverridesUsesTheReasonStageChain() {
        assertEquals(List.of(
                        "REJECTION_SALARY_EXPECTATIONS_POST_INTERVIEW",
                        "REJECTION_SALARY_EXPECTATIONS",
                        "REJECTION_POST_INTERVIEW"),
                CandidateMailerReactor.rejectionKeys(payload(
                        "reason_code", "SALARY_EXPECTATIONS", "from_stage", "INTERVIEW_2")));
    }

    @Test
    void anExplicitTemplateChoiceReplacesTheChainRatherThanLeadingIt() {
        // Following it with the chain would mean a recruiter whose chosen
        // letter is inactive gets a DIFFERENT letter sent in their name.
        assertEquals(List.of("REJECTION_LOCATION_LANGUAGE"),
                CandidateMailerReactor.rejectionKeys(payload(
                        "reason_code", "PROFILE_MISMATCH", "from_stage", "SCREENING",
                        "email_template_key", "  REJECTION_LOCATION_LANGUAGE  ")));
    }

    @Test
    void optingOutOfTheEmailResolvesToNoKeysAtAll() {
        assertTrue(CandidateMailerReactor.rejectionKeys(payload(
                "reason_code", "OTHER", "from_stage", "SCREENING",
                "suppress_email", true)).isEmpty());
    }

    @Test
    void aBlankOverrideIsNotAnOverride() {
        assertEquals(List.of("REJECTION_SCREENING"),
                CandidateMailerReactor.rejectionKeys(payload(
                        "from_stage", "SCREENING", "email_template_key", "   ")));
    }

    @Test
    void enteringThePoolMailsAndReBucketingDoesNot() {
        assertEquals(List.of("POOLED_PROSPECT", "POOLED"),
                CandidateMailerReactor.pooledKeys(payload(
                        "pool_status", "PROSPECT", "entered_pool", true)));
        assertTrue(CandidateMailerReactor.pooledKeys(payload(
                "pool_status", "CONTACTED", "entered_pool", false)).isEmpty());
    }

    @Test
    void aPoolEventWithoutTheFlagStillMails() {
        // entered_pool was added with this work; an event recorded without
        // it is a genuine pooling, not a re-bucket.
        assertEquals(List.of("POOLED_SILVER_MEDALIST", "POOLED"),
                CandidateMailerReactor.pooledKeys(payload("pool_status", "SILVER_MEDALIST")));
    }

    @Test
    void aTriggerThatDoesNotApplyResolvesToNoKeys() {
        assertTrue(CandidateMailerReactor.singleKey(null).isEmpty());
        assertEquals(List.of("ACKNOWLEDGEMENT"),
                CandidateMailerReactor.singleKey("ACKNOWLEDGEMENT"));
    }
}
