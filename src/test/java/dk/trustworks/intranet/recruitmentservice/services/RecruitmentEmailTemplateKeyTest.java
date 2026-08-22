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
    void jobOwnedKeys_areSystemOwned_andNotComposableByHand() {
        // F10 (remediation 2026-08-22): the Method B booking link exists only
        // inside the advance sweep, and the candidate invitation is an
        // Outlook event body whose interview extras resolve only in the
        // calendar service. Hand-sending either can only produce a broken
        // message, exactly like the consent renewal.
        assertTrue(RecruitmentEmailService.isSystemKey(
                RecruitmentSchedulingCandidateService.TEMPLATE_KEY_OPTION_INVITATION));
        assertTrue(RecruitmentEmailService.isSystemKey(
                RecruitmentEmailService.KEY_INTERVIEW_CANDIDATE_INVITATION));
        assertFalse(RecruitmentEmailService.isTriggerKey(
                RecruitmentSchedulingCandidateService.TEMPLATE_KEY_OPTION_INVITATION));
        assertFalse(RecruitmentEmailService.isTriggerKey(
                RecruitmentEmailService.KEY_INTERVIEW_CANDIDATE_INVITATION));
    }

    @Test
    void receiptKeys_areTriggers_notSystemOwned() {
        // F6/F7: both receipts are reactor-fired, and re-sending one by hand
        // is legitimate — so they must classify exactly like ACKNOWLEDGEMENT.
        for (String key : new String[]{
                RecruitmentEmailService.KEY_UNSOLICITED_ACKNOWLEDGEMENT,
                RecruitmentEmailService.KEY_DUPLICATE_APPLICATION_NOTICE}) {
            assertTrue(RecruitmentEmailService.isTriggerKey(key),
                    key + " must be reactor-fired");
            assertFalse(RecruitmentEmailService.isSystemKey(key),
                    key + " must stay available in the compose picker");
        }
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
