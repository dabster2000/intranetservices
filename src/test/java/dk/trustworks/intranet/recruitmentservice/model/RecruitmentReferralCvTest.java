package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralClosedReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralRelation;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional CV a referrer may attach, at the aggregate level: a referral
 * carries AT MOST ONE CV, accepts it only while it awaits triage, and hands
 * back the superseded file uuid so the caller can delete the S3 object it
 * replaced rather than orphaning it.
 */
class RecruitmentReferralCvTest {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private RecruitmentReferral submittedReferral() {
        RecruitmentReferral referral = new RecruitmentReferral();
        referral.setUuid("referral-under-test");
        referral.setReferrerUuid(ACTOR.toString());
        referral.setReferrerRelation(RecruitmentReferralRelation.COLLEAGUE);
        referral.setCandidateName("Jane Larsen");
        referral.setWhyText("Ran the platform migration at my last job.");
        referral.setStatus(RecruitmentReferralStatus.SUBMITTED);
        return referral;
    }

    // ---- Attaching ----------------------------------------------------------------

    @Test
    void freshReferralCarriesNoCv() {
        RecruitmentReferral referral = submittedReferral();

        assertFalse(referral.hasCv());
        assertNull(referral.getCvFileUuid());
        assertNull(referral.getCvFilename());
    }

    @Test
    void attachRecordsEveryFactAndReportsNothingReplaced() {
        RecruitmentReferral referral = submittedReferral();

        String replaced = referral.attachCv("file-1", "jane-cv.pdf", "application/pdf", 2048);

        assertNull(replaced, "the first attachment supersedes nothing");
        assertTrue(referral.hasCv());
        assertEquals("file-1", referral.getCvFileUuid());
        assertEquals("jane-cv.pdf", referral.getCvFilename());
        assertEquals("application/pdf", referral.getCvContentType());
        assertEquals(2048, referral.getCvSizeBytes());
        assertNotNull(referral.getCvUploadedAt());
    }

    /**
     * Re-attaching REPLACES. The returned uuid is the caller's cue to delete
     * the old object — without it, every re-upload would leak an S3 file that
     * nothing references and no sweep would ever find.
     */
    @Test
    void reattachReplacesAndHandsBackTheSupersededFile() {
        RecruitmentReferral referral = submittedReferral();
        referral.attachCv("file-1", "old.pdf", "application/pdf", 10);

        String replaced = referral.attachCv("file-2", "new.pdf", "application/pdf", 20);

        assertEquals("file-1", replaced);
        assertEquals("file-2", referral.getCvFileUuid());
        assertEquals("new.pdf", referral.getCvFilename());
    }

    // ---- The SUBMITTED-only window --------------------------------------------------

    @Test
    void triagedReferralRejectsACv() {
        RecruitmentReferral referral = submittedReferral();
        referral.triageToCandidate(UUID.randomUUID().toString(), false, ACTOR);

        assertThrows(BusinessRuleViolation.class,
                () -> referral.attachCv("file-1", "late.pdf", "application/pdf", 10));
    }

    @Test
    void dismissedReferralRejectsACv() {
        RecruitmentReferral referral = submittedReferral();
        referral.dismiss(RecruitmentReferralClosedReason.NOT_RELEVANT, ACTOR);

        assertThrows(BusinessRuleViolation.class,
                () -> referral.attachCv("file-1", "late.pdf", "application/pdf", 10));
    }

    // ---- Clearing -------------------------------------------------------------------

    /**
     * The dismiss leg's second half: no candidate will ever own this file, so
     * the row must stop referencing it. Every column goes, not just the uuid —
     * the filename is PII in its own right.
     */
    @Test
    void clearCvForgetsEveryColumn() {
        RecruitmentReferral referral = submittedReferral();
        referral.attachCv("file-1", "jane-cv.pdf", "application/pdf", 2048);

        referral.clearCv();

        assertFalse(referral.hasCv());
        assertNull(referral.getCvFileUuid());
        assertNull(referral.getCvFilename());
        assertNull(referral.getCvContentType());
        assertNull(referral.getCvSizeBytes());
        assertNull(referral.getCvUploadedAt());
    }

    @Test
    void clearCvIsSafeWhenNoneWasAttached() {
        RecruitmentReferral referral = submittedReferral();

        referral.clearCv();

        assertFalse(referral.hasCv());
    }

    /** A blank uuid is not a CV — hasCv must not be fooled by an empty string. */
    @Test
    void blankFileUuidDoesNotCountAsAnAttachment() {
        RecruitmentReferral referral = submittedReferral();
        referral.setCvFileUuid("   ");

        assertFalse(referral.hasCv());
    }

    // ---- Triage is unaffected by the CV ---------------------------------------------

    /**
     * Attaching a CV must not disturb the one-shot triage: the referral still
     * moves SUBMITTED → TRIAGED exactly once, and the pointer survives the
     * transition so the service can hand the file to the new candidate.
     */
    @Test
    void triageStillWorksAndKeepsThePointerForTheHandover() {
        RecruitmentReferral referral = submittedReferral();
        referral.attachCv("file-1", "jane-cv.pdf", "application/pdf", 2048);
        String candidateUuid = UUID.randomUUID().toString();

        referral.triageToCandidate(candidateUuid, false, ACTOR);

        assertEquals(RecruitmentReferralStatus.TRIAGED, referral.getStatus());
        assertEquals(candidateUuid, referral.getCandidateUuid());
        assertTrue(referral.hasCv(), "the service re-links this file to the candidate");
        assertThrows(BusinessRuleViolation.class,
                () -> referral.triageToCandidate(UUID.randomUUID().toString(), false, ACTOR));
    }
}
