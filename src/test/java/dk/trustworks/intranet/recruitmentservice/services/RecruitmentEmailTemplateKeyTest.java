package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a template key classifies, which decides where the template may be
 * used. The two classifications are independent and mean different things:
 * <ul>
 *   <li><b>trigger</b> — the candidate mailer fires it on an event. Composing
 *       one by hand is legitimate ("send the rejection again").</li>
 *   <li><b>system</b> — a scheduled job owns it end to end. Composing one by
 *       hand cannot work, because the job is what supplies the merge values
 *       the body needs.</li>
 * </ul>
 */
class RecruitmentEmailTemplateKeyTest {

    @Test
    void consentRenewal_isSystemOwned_andThereforeNotComposableByHand() {
        // The whole point: {{consent_link}} is minted by the GDPR sweep, so a
        // manual send of this template mails a dead link and the candidate is
        // auto-deleted at the retention deadline for not clicking it.
        assertTrue(RecruitmentEmailService.isSystemKey(
                RecruitmentGdprService.KEY_CONSENT_RENEWAL));
    }

    @Test
    void consentRenewal_isNotATriggerKey() {
        // It is not reactor-driven either — no event fires it. Both answers
        // are needed: "not a trigger" is what previously made it look like an
        // ordinary manual-send template.
        assertFalse(RecruitmentEmailService.isTriggerKey(
                RecruitmentGdprService.KEY_CONSENT_RENEWAL));
    }

    @Test
    void recruiterFacingKeys_stayComposable() {
        for (String key : new String[]{
                RecruitmentEmailService.KEY_ACKNOWLEDGEMENT,
                RecruitmentEmailService.KEY_REJECTION_SCREENING,
                RecruitmentEmailService.KEY_REJECTION_POST_INTERVIEW,
                "STAGE_INTERVIEW_1",
                "ART14_NOTICE",
                "SOME_CUSTOM_TEMPLATE"}) {
            assertFalse(RecruitmentEmailService.isSystemKey(key),
                    key + " must stay available in the compose picker");
        }
    }

    @Test
    void nullKey_isNeitherClassification() {
        assertFalse(RecruitmentEmailService.isSystemKey(null));
        assertFalse(RecruitmentEmailService.isTriggerKey(null));
    }
}
