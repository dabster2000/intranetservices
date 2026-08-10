package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rich-text render path. The escape moved from the whole body (which is
 * now markup we mean to keep) onto the merge values (which are data an
 * outsider can control), so these tests exist mainly to prove that swap did
 * not open a hole.
 */
class RecruitmentEmailRichTextRenderTest {

    private static RecruitmentCandidate candidate(String first, String last) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName(first);
        candidate.setLastName(last);
        return candidate;
    }

    private static RecruitmentEmailRenderer.Rendered renderHtml(String body,
                                                                RecruitmentCandidate candidate) {
        return RecruitmentEmailRenderer.render("Emne", body, candidate, null, Map.of(),
                RecruitmentEmailBodyFormat.HTML);
    }

    // ---- The security swap ------------------------------------------------

    @Test
    void aCandidateCannotInjectMarkupThroughTheirOwnName() {
        // The name comes straight off the public application form. Before rich
        // text it was safe because the whole body was escaped at send time;
        // now the value must be escaped as it is substituted instead.
        RecruitmentEmailRenderer.Rendered rendered = renderHtml(
                "<p>Kære {{candidate_first_name}}</p>",
                candidate("<script>alert(1)</script>", "Jensen"));

        assertFalse(rendered.body().contains("<script>"));
        assertTrue(rendered.body().contains("&lt;script&gt;"));
    }

    @Test
    void aNameWithAnAmpersandIsEscapedNotMangled() {
        RecruitmentEmailRenderer.Rendered rendered =
                renderHtml("<p>{{candidate_full_name}}</p>", candidate("Ann & Bo", "R&D"));

        assertEquals("<p>Ann &amp; Bo R&amp;D</p>", rendered.body());
    }

    @Test
    void theSurroundingMarkupIsKeptExactly() {
        RecruitmentEmailRenderer.Rendered rendered = renderHtml(
                "<p>Kære <strong>{{candidate_first_name}}</strong></p><ul><li>Et</li></ul>",
                candidate("Søren", "Kjærgård"));

        assertEquals("<p>Kære <strong>Søren</strong></p><ul><li>Et</li></ul>", rendered.body());
    }

    @Test
    void theSubjectIsStillPlainTextEvenForAnHtmlBody() {
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                "Hej {{candidate_first_name}}", "<p>x</p>",
                candidate("Ann & Bo", "Jensen"), null, Map.of(), RecruitmentEmailBodyFormat.HTML);

        assertEquals("Hej Ann & Bo", rendered.subject());
    }

    // ---- Tokens the editor broke up --------------------------------------

    @Test
    void aTokenFormattedAcrossTagsStillResolves() {
        // Bolding half a placeholder is a plausible slip in a WYSIWYG editor.
        // Unhealed, the regex would not match and the candidate would receive
        // the literal braces — with the unresolved-token warning silent,
        // because the warning uses the same regex.
        RecruitmentEmailRenderer.Rendered rendered = renderHtml(
                "<p>Kære {{candidate_<b>first</b>_name}}</p>", candidate("Anna", "Jensen"));

        assertEquals("<p>Kære Anna</p>", rendered.body());
        assertTrue(rendered.unresolvedFields().isEmpty());
    }

    @Test
    void aTokenWithANonBreakingSpaceStillResolves() {
        // Browsers insert &nbsp; freely in contentEditable, and \s does not
        // match U+00A0.
        assertEquals("<p>Anna</p>",
                renderHtml("<p>{{&nbsp;candidate_first_name&nbsp;}}</p>",
                        candidate("Anna", "Jensen")).body());
    }

    @Test
    void aLongRunOfTokenCharactersDoesNotBlowTheStack() {
        // A naive (?:a|b|c)* recurses about one JVM frame per matched
        // character, so this — well inside the 16 000-char cap and storable by
        // anyone with recruitment:write — used to throw StackOverflowError.
        // That is an Error, so the reactor chassis' poison-skip would miss it
        // and the candidate mailer's watermark would wedge permanently.
        String body = "<p>{{" + "a".repeat(6000) + "}}</p>";
        RecruitmentEmailRenderer.Rendered rendered =
                renderHtml(body, candidate("Anna", "Jensen"));
        assertTrue(rendered.body().contains("aaaa"));
    }

    @Test
    void healingNeverDamagesASpanThatIsNotAToken() {
        // Bolding "Hej {{candidate" puts the </b> inside the braces. Stripping
        // it unconditionally would leave the paragraph unbalanced and jsoup
        // would bold the whole sentence.
        assertEquals("<p><b>Hej {{candidate</b>_navn}}</p>",
                renderHtml("<p><b>Hej {{candidate</b>_navn}}</p>",
                        candidate("Anna", "Jensen")).body());
        // Stray braces spanning two blocks must not swallow the markup between.
        assertEquals("<p>Brug {{ her</p><p>og }} der</p>",
                renderHtml("<p>Brug {{ her</p><p>og }} der</p>",
                        candidate("Anna", "Jensen")).body());
    }

    @Test
    void aBodyThatSanitizesToNothingIsRejectedRatherThanSent() {
        // U+FEFF is Cf, so text() sees it and String.isBlank() does not — the
        // required-body check passes and clean() then removes it. Without the
        // post-sanitize re-check the candidate receives an empty email.
        assertFalse(RecruitmentEmailService.isBlankBody("<p>\uFEFF</p>",
                        RecruitmentEmailBodyFormat.HTML),
                "precondition: the raw check does not catch this");
        assertTrue(RecruitmentEmailHtmlSanitizer.isBlankHtml(
                        RecruitmentEmailService.sanitizeBodyForStorage(
                                "<p>\uFEFF</p>", RecruitmentEmailBodyFormat.HTML)),
                "…but it is empty once sanitized, which is what the send path re-checks");
    }

    @Test
    void healingDoesNotTouchMarkupOutsideTokens() {
        assertEquals("<p>Kære <b>Anna</b> {{ukendt}}</p>",
                renderHtml("<p>Kære <b>{{candidate_first_name}}</b> {{ukendt}}</p>",
                        candidate("Anna", "Jensen")).body());
    }

    @Test
    void anUnknownTokenIsStillReportedOnTheHtmlPath() {
        RecruitmentEmailRenderer.Rendered rendered =
                renderHtml("<p>{{salary_offer}}</p>", candidate("Anna", "Jensen"));

        assertTrue(rendered.body().contains("{{salary_offer}}"));
        assertTrue(rendered.unresolvedFields().contains("salary_offer"));
    }

    // ---- The consent link -------------------------------------------------

    @Test
    void theConsentLinkBecomesAnAnchorInAnHtmlBody() {
        // In a plain body the raw URL is clickable because mail clients
        // autolink it. Inside markup it is dead text unless we make an anchor —
        // and a candidate who cannot click it gets deleted for not consenting.
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                "Emne", "<p>Klik her: {{consent_link}}</p>", null, null,
                Map.of("consent_link", "https://intra.trustworks.dk/consent/abc123"),
                RecruitmentEmailBodyFormat.HTML);

        assertTrue(rendered.body().contains(
                "<a href=\"https://intra.trustworks.dk/consent/abc123\">"
                        + "https://intra.trustworks.dk/consent/abc123</a>"));
    }

    @Test
    void theConsentLinkStaysPlainInAPlainBody() {
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                "Emne", "Klik her: {{consent_link}}", null, null,
                Map.of("consent_link", "https://intra.trustworks.dk/consent/abc123"));

        assertEquals("Klik her: https://intra.trustworks.dk/consent/abc123", rendered.body());
    }

    // ---- The send-time conversion ----------------------------------------

    @Test
    void toHtml_sanitizesOnTheWayOutEvenIfTheStoredBodyWasNot() {
        // A body can be written by one release and sent by the next, and the
        // review queue holds text a recruiter edited by hand — so the send
        // path never assumes the stored body was already clean.
        String mail = RecruitmentEmailRenderer.toHtml(
                "<p>Hej</p><script>alert(1)</script>", RecruitmentEmailBodyFormat.HTML);

        assertFalse(mail.contains("script"));
        assertTrue(mail.contains("<p>Hej</p>"));
        assertTrue(mail.startsWith("<div style=\"font-family: sans-serif; white-space: normal;\">"));
    }

    @Test
    void toHtml_plainPathIsByteIdenticalToTheLegacyBehaviour() {
        String legacy = RecruitmentEmailRenderer.toHtml("Kære <Anna>\nHilsen");
        assertEquals(legacy,
                RecruitmentEmailRenderer.toHtml("Kære <Anna>\nHilsen", RecruitmentEmailBodyFormat.PLAIN));
        assertEquals(legacy,
                RecruitmentEmailRenderer.toHtml("Kære <Anna>\nHilsen", null));
    }

    // ---- Blankness --------------------------------------------------------

    @Test
    void anEmptyRichEditorIsABlankBody() {
        assertTrue(RecruitmentEmailService.isBlankBody("<p><br></p>", RecruitmentEmailBodyFormat.HTML));
        assertFalse(RecruitmentEmailService.isBlankBody("<p>Hej</p>", RecruitmentEmailBodyFormat.HTML));
        // The plain path keeps its old, cheaper rule.
        assertTrue(RecruitmentEmailService.isBlankBody("   ", RecruitmentEmailBodyFormat.PLAIN));
        assertFalse(RecruitmentEmailService.isBlankBody("<p><br></p>", RecruitmentEmailBodyFormat.PLAIN));
    }

    // ---- The discriminator ------------------------------------------------

    @Test
    void anUnknownOrAbsentFormatFallsBackToPlain() {
        // A pre-rich-text client sends no format at all; nothing it stores may
        // be reinterpreted as markup.
        assertEquals(RecruitmentEmailBodyFormat.PLAIN, RecruitmentEmailBodyFormat.parse(null));
        assertEquals(RecruitmentEmailBodyFormat.PLAIN, RecruitmentEmailBodyFormat.parse(""));
        assertEquals(RecruitmentEmailBodyFormat.PLAIN, RecruitmentEmailBodyFormat.parse("nonsense"));
        assertEquals(RecruitmentEmailBodyFormat.HTML, RecruitmentEmailBodyFormat.parse("html"));
    }

    @Test
    void storageSanitizationOnlyAppliesToTheHtmlPath() {
        assertEquals("5 < 6",
                RecruitmentEmailService.sanitizeBodyForStorage("5 < 6", RecruitmentEmailBodyFormat.PLAIN));
        assertEquals("<p>Hej</p>",
                RecruitmentEmailService.sanitizeBodyForStorage(
                        "<p onclick=\"x\">Hej</p>", RecruitmentEmailBodyFormat.HTML));
    }
}
