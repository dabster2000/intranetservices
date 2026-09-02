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

    // ---- The clock the letter's deletion sentence depends on -------------

    @Test
    void enteringThePoolStartsASixMonthClock() {
        // Before this, CandidateService.pool set no deadline at all, so a
        // manually pooled or unsolicited candidate was never swept and never
        // asked to renew -- a candidate bank that only grew. The letter's
        // "we delete after six months" sentence is only true because of it.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        assertEquals(now.plusMonths(6),
                CandidateService.retentionDeadlineOnPooling(null, null, now));
    }

    @Test
    void poolingNeverShortensAnExistingDeadline() {
        // A candidate who already granted consent holds a 12-month deadline.
        // Pooling them again is not a reason to bring their data's life
        // forward -- that would silently revoke half of what they were
        // promised when they said yes.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        LocalDateTime granted = now.plusMonths(12);
        assertEquals(granted, CandidateService.retentionDeadlineOnPooling(granted, null, now));
    }

    // ---- ...and the promise the deadline column does not carry -----------

    @Test
    void aGrantedConsentWithNoDeadlineOfItsOwnStillHoldsTheClockOpen() {
        // THE production case (37 candidates on 2026-09-02): consent GRANTED
        // with a future expires_at, retention_deadline NULL beside it. The
        // column-only comparison read that as "no constraint" and stamped six
        // fresh months over an eleven-month promise. Staging reproduced it on
        // candidate 863c0d00: consent to 2027-08-02, pooling wrote 2027-03-02.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        LocalDateTime consentExpiry = LocalDateTime.of(2027, 8, 2, 12, 0);
        assertEquals(consentExpiry,
                CandidateService.retentionDeadlineOnPooling(null, consentExpiry, now));
    }

    @Test
    void theLatestOfTheThreeWinsWhicheverItIs() {
        // The rule is a max(), not a precedence order -- each of the three can
        // legitimately be the furthest out, and shortening to any of the other
        // two would be deleting data something still vouches for.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        LocalDateTime near = now.plusMonths(2);
        LocalDateTime far = now.plusMonths(18);
        assertEquals(far, CandidateService.retentionDeadlineOnPooling(far, near, now));
        assertEquals(far, CandidateService.retentionDeadlineOnPooling(near, far, now));
        // Neither beats the fresh window, so pooling still restarts the clock.
        assertEquals(now.plusMonths(6),
                CandidateService.retentionDeadlineOnPooling(near, near, now));
    }

    @Test
    void aLapsedConsentCannotShortenAnything() {
        // An EXPIRED row keeps its old expires_at, and a REQUESTED one may
        // carry a stale date. Neither is a promise any more, and max() is what
        // makes them harmless rather than a way to shorten the window.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        assertEquals(now.plusMonths(6),
                CandidateService.retentionDeadlineOnPooling(null, now.minusMonths(3), now));
        assertEquals(now.plusMonths(12), CandidateService.retentionDeadlineOnPooling(
                now.plusMonths(12), now.minusMonths(3), now));
    }

    @Test
    void aStaleDeadlineIsRefreshedRatherThanKept() {
        // A deadline already in the past would mean the sweep deletes them
        // before the letter asking to keep them could be answered.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        assertEquals(now.plusMonths(6),
                CandidateService.retentionDeadlineOnPooling(now.minusMonths(1), null, now));
        // Same for a deadline sooner than the fresh window.
        assertEquals(now.plusMonths(6),
                CandidateService.retentionDeadlineOnPooling(now.plusMonths(2), null, now));
    }

    @Test
    void theConsentTokenOutlivesTheClockItStarts() {
        // The two have to agree: a token that expired before the retention
        // deadline would delete a candidate for not answering a dead link.
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        LocalDateTime deadline = CandidateService.retentionDeadlineOnPooling(null, null, now);
        RecruitmentCandidate pooled = candidateWithDeadline(deadline);
        assertEquals(deadline, RecruitmentConsentService.poolTokenExpiry(pooled, now));
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
