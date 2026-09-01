package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the one string this feature is judged on: the DM an
 * employee receives when a stranger has named them on a public job
 * application.
 * <p>
 * The named employee is the data subject of an assertion they had no part
 * in, so the message must say three things, and this test refuses to let
 * any of the three be edited away: the APPLICANT wrote it, we have NOT
 * verified it, and it is NOT a recommendation from the recipient. The
 * sibling {@link ReferrerNotificationReactor} says "Your referral …" —
 * borrowing that voice here would tell a lie in the employee's own Slack.
 */
class ApplicantReferrerNotificationDmTest {

    private final ApplicantReferrerNotificationReactor reactor =
            new ApplicantReferrerNotificationReactor();

    private static RecruitmentCandidate candidate(String first, String last) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName(first);
        candidate.setLastName(last);
        return candidate;
    }

    @Test
    void dm_namesTheApplicant() {
        assertTrue(reactor.dmText(candidate("Mette", "Krogh")).contains("*Mette Krogh*"));
    }

    @Test
    void dm_saysTheClaimIsTheApplicantsOwn() {
        String text = reactor.dmText(candidate("Mette", "Krogh"));
        assertTrue(text.contains("ansøgerens egen oplysning"),
                "the DM must attribute the claim to the applicant");
    }

    @Test
    void dm_saysTheClaimIsUnverified() {
        assertTrue(reactor.dmText(candidate("Mette", "Krogh"))
                        .contains("Vi har ikke bekræftet den"),
                "the DM must say the claim is unverified");
    }

    @Test
    void dm_saysItIsNotARecommendationFromTheRecipient() {
        String text = reactor.dmText(candidate("Mette", "Krogh"));
        assertTrue(text.contains("ikke en anbefaling fra dig"),
                "the DM must not let the recipient read this as their own referral");
        assertTrue(text.contains("du har ikke anbefalet nogen"));
    }

    @Test
    void dm_neverBorrowsTheReferralVoice() {
        String text = reactor.dmText(candidate("Mette", "Krogh")).toLowerCase();
        assertFalse(text.contains("din anbefaling af"),
                "this is not a referral the employee made");
        assertFalse(text.contains("tak for"),
                "there is nothing to thank the recipient for — they did nothing");
    }

    @Test
    void dm_carriesNoPositionOrStage() {
        // §P6 "no candidate handle": the recipient has no stake in a pipeline
        // they never opened, so the DM is the name and the disclaimer only.
        String text = reactor.dmText(candidate("Mette", "Krogh")).toLowerCase();
        assertFalse(text.contains("stilling"));
        assertFalse(text.contains("screening"));
        assertFalse(text.contains("interview"));
    }

    @Test
    void applicantNames_areMrkdwnEscaped() {
        // A public-form applicant naming themselves
        // "<https://evil.example|Klik her>" must not render as a live link
        // in a colleague's DM.
        String text = reactor.dmText(candidate("<https://evil.example|Klik", "her>"));
        assertFalse(text.contains("<https://evil.example|Klik her>"));
        // Since the 2026-09-01 security review this path is stronger than the
        // shared mrkdwnSafe: the URL is not merely escaped into inert text, it
        // is removed, because Slack auto-links a bare https:// anyway.
        assertFalse(text.contains("evil.example"), "no host reaches the recipient at all");
        // The label glued to the URL ("|Klik") goes with it — the pattern runs
        // to the next whitespace. Acceptable: a name carrying a URL is hostile
        // by construction, and over-deleting a hostile name costs nothing.
        assertFalse(text.contains("Klik"), "the label glued to the URL goes with it");
        assertTrue(text.contains("En jobansøger har nævnt dit navn"),
                "the notice itself is unharmed by a hostile name");
    }

    @Test
    void nameless_candidateStillProducesAReadableMessage() {
        // Map.of-style NPEs and empty bolds are how a notice silently becomes
        // no notice at all.
        String text = reactor.dmText(candidate(null, null));
        assertTrue(text.contains("*En ansøger*"));
        assertFalse(text.contains("**"));
    }

    @Test
    void reactorName_isStable() {
        // The recruitment_reactor_offsets primary key: renaming a deployed
        // reactor re-seeds its watermark to the stream head.
        assertEquals("applicant-referrer-notifications", reactor.name());
    }

    // ---- Security review 2026-09-01 -------------------------------------
    // The applicant controls their own name AND, on this path, chooses who
    // receives the DM. Everywhere else applicant text lands in the shared
    // recruitment channel; here it lands in one named colleague's Slack, so
    // the name is flattened before it is wrapped in *…*.

    @Test
    void inlineSafe_stripsNewlinesSoNoBlockCanBeInjected() {
        String hostile = "Ida\n\n:rotating_light: Sikkerhedsadvarsel";
        String safe = ApplicantReferrerNotificationReactor.inlineSafe(hostile);
        assertFalse(safe.contains("\n"), "a name must not carry its own paragraphs");
        assertTrue(safe.startsWith("Ida"));
    }

    @Test
    void inlineSafe_removesBareUrlsSlackWouldAutoLink() {
        // mrkdwnSafe escapes < and >, which kills <url|label> — but Slack
        // linkifies a bare https:// with no angle brackets at all.
        String safe = ApplicantReferrerNotificationReactor.inlineSafe(
                "Ida https://evil.example/login");
        assertFalse(safe.contains("https://"), "no live link may reach the recipient");
        assertFalse(safe.contains("evil.example"));
        assertTrue(safe.contains("Ida"));
    }

    @Test
    void inlineSafe_removesWwwHostsToo() {
        String safe = ApplicantReferrerNotificationReactor.inlineSafe("Ida www.evil.example");
        assertFalse(safe.contains("www.evil.example"));
    }

    @Test
    void inlineSafe_stripsEmphasisSoTheNameCannotBreakOutOfItsBold() {
        String safe = ApplicantReferrerNotificationReactor.inlineSafe("Ida* er *chef");
        assertFalse(safe.contains("*"), "emphasis characters would re-open the *…* wrapper");
    }

    @Test
    void inlineSafe_nullIsEmptyNotNull() {
        assertEquals("", ApplicantReferrerNotificationReactor.inlineSafe(null));
    }

    @Test
    void dm_hostileNameCannotInjectALinkOrABlock() {
        String text = reactor.dmText(
                candidate("Ida\n\n:rotating_light: *Advarsel* https://evil.example", "Iversen"));
        assertFalse(text.contains("https://evil.example"), "phishing link must not survive");
        // Exactly the paragraphs the template itself defines — the name added none.
        assertEquals(5, text.split("\n\n").length,
                "a name must not be able to add paragraphs to the message");
    }

    @Test
    void dm_linksTheArt14NoticeTheRecipientIsEntitledTo() {
        String text = reactor.dmText(candidate("Mette", "Krogh"));
        assertTrue(text.contains(ApplicantReferrerNotificationReactor.PRIVACY_NOTICE_PATH),
                "the DM is the Art. 14 notification — it must point at the notice");
        assertTrue(text.contains("afsnit 6"), "and at the section that is about the recipient");
    }

    @Test
    void recipientCap_isAStatedNumberNotAMagicOne() {
        assertTrue(ApplicantReferrerNotificationReactor.MAX_DMS_PER_RECIPIENT > 0);
        assertEquals(24, ApplicantReferrerNotificationReactor.RECIPIENT_WINDOW.toHours());
    }
}
