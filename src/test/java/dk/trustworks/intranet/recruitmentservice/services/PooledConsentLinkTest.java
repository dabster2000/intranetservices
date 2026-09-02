package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two decisions the candidate mailer makes before it mints a talent-pool
 * consent token (2026-09-02): <em>does this letter actually ask?</em> and
 * <em>how long does the answer stay possible?</em>
 * <p>
 * Both matter more than they look. A mint is a GDPR record; minting for a
 * letter that never asks writes down a question nobody was put, and a token
 * that expires before the letter clears the review queue silently turns the
 * candidate's only way to answer into a dead link.
 */
class PooledConsentLinkTest {

    private static final String LINK = "consent_link";

    private static RecruitmentCandidate candidateWithDeadline(LocalDateTime deadline) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setRetentionDeadline(deadline);
        return candidate;
    }

    // ---- Does the letter ask? -------------------------------------------

    @Test
    void aLetterCarryingTheTokenAsks() {
        assertTrue(RecruitmentEmailRenderer.usesToken(
                "Må vi gemme din profil?",
                "<p>Sig ja her: {{consent_link}}</p>",
                RecruitmentEmailBodyFormat.HTML, LINK));
    }

    @Test
    void aLetterWithoutItDoesNot() {
        // The pre-2026-09-02 pooled letter: it asks "Er det ok for dig?" in
        // prose, with nothing to click. No token, so no mint.
        assertFalse(RecruitmentEmailRenderer.usesToken(
                "Tak for din ansøgning",
                "<p>Er det ok for dig?</p><p>Mange hilsner</p>",
                RecruitmentEmailBodyFormat.HTML, LINK));
    }

    @Test
    void aTokenTheRichEditorSplitStillCounts() {
        // Someone bolded half the placeholder in the editor. substitute()
        // heals this and fills the link, so the mint has to happen too --
        // otherwise the healed token resolves to nothing and the send gate
        // blocks a letter that looked fine in the editor.
        assertTrue(RecruitmentEmailRenderer.usesToken(
                "Emne",
                "<p>{{consent<b>_</b>link}}</p>",
                RecruitmentEmailBodyFormat.HTML, LINK));
    }

    @Test
    void aTokenInTheSubjectCounts() {
        assertTrue(RecruitmentEmailRenderer.usesToken(
                "Svar her: {{consent_link}}", "<p>Hej</p>",
                RecruitmentEmailBodyFormat.HTML, LINK));
    }

    @Test
    void plainBodiesAreReadAsPlain() {
        assertTrue(RecruitmentEmailRenderer.usesToken(
                "Emne", "Sig ja her: {{consent_link}}",
                RecruitmentEmailBodyFormat.PLAIN, LINK));
        // No healing on a plain body -- the markup IS the text there, so a
        // "split" token is genuinely just characters and asks for nothing.
        assertFalse(RecruitmentEmailRenderer.usesToken(
                "Emne", "{{consent<b>_</b>link}}",
                RecruitmentEmailBodyFormat.PLAIN, LINK));
    }

    @Test
    void anotherLinkTokenIsNotThisOne() {
        assertFalse(RecruitmentEmailRenderer.usesToken(
                "Emne", "<p>{{options_link}}</p>",
                RecruitmentEmailBodyFormat.HTML, LINK));
        assertFalse(RecruitmentEmailRenderer.usesToken(
                "Emne", "<p>x</p>", RecruitmentEmailBodyFormat.HTML, null));
    }

    // ---- How long does the answer stay possible? -------------------------

    @Test
    void aCandidateWithARetentionDeadlineGetsATokenPinnedToIt() {
        // Same rule the renewal sweep uses: the link dies with the data it
        // is about, never after it.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        LocalDateTime deadline = now.plusMonths(3);
        assertEquals(deadline, RecruitmentConsentService.poolTokenExpiry(
                candidateWithDeadline(deadline), now));
    }

    @Test
    void aCandidateWithoutOneGetsTheStandardRetentionWindow() {
        // The common case for this letter: CandidateService.pool sets no
        // deadline, and an unsolicited applicant never had an application
        // terminate to start the clock.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        assertEquals(now.plusMonths(RecruitmentConsentService.DEFAULT_POOL_TOKEN_MONTHS),
                RecruitmentConsentService.poolTokenExpiry(candidateWithDeadline(null), now));
        assertEquals(now.plusMonths(RecruitmentConsentService.DEFAULT_POOL_TOKEN_MONTHS),
                RecruitmentConsentService.poolTokenExpiry(null, now));
    }

    @Test
    void anAlreadyPassedDeadlineDoesNotMintADeadLink() {
        // resolve() rejects an expired token, so pinning to a deadline in the
        // past would mail a link that is dead on arrival.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        assertEquals(now.plusMonths(RecruitmentConsentService.DEFAULT_POOL_TOKEN_MONTHS),
                RecruitmentConsentService.poolTokenExpiry(
                        candidateWithDeadline(now.minusDays(1)), now));
    }

    @Test
    void theWindowOutlastsTheReviewQueue() {
        // The pooled letter defaults to review-first and the token is minted
        // when it is QUEUED, not when it is approved -- queueForReview stores
        // the rendered body and approve sends it verbatim. A window measured
        // in months is what makes that safe.
        assertTrue(RecruitmentConsentService.DEFAULT_POOL_TOKEN_MONTHS >= 1);
    }
}
