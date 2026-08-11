package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P15 DoD: merge fields render correctly with Danish characters; unknown
 * tokens stay visible; the plain-text → HTML conversion is escape-safe.
 */
class RecruitmentEmailRendererTest {

    private static RecruitmentCandidate candidate(String first, String last) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName(first);
        candidate.setLastName(last);
        return candidate;
    }

    private static RecruitmentPosition position(String title) {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setTitle(title);
        return position;
    }

    @Test
    void mergeFields_renderWithDanishCharacters() {
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                "Vi har modtaget din ansøgning – {{position_title}}",
                "Kære {{candidate_first_name}} {{candidate_last_name}}\n\nTak for din ansøgning.",
                candidate("Søren", "Kjærgård"), position("Løsningsarkitekt"));

        assertEquals("Vi har modtaget din ansøgning – Løsningsarkitekt", rendered.subject());
        assertTrue(rendered.body().startsWith("Kære Søren Kjærgård\n"));
        assertTrue(rendered.unresolvedFields().isEmpty());
    }

    @Test
    void fullName_andWhitespaceInsideBraces_bothResolve() {
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                "Hej {{ candidate_full_name }}", "{{candidate_full_name}}",
                candidate("Anna", "Jensen"), null);

        assertEquals("Hej Anna Jensen", rendered.subject());
        assertEquals("Anna Jensen", rendered.body());
    }

    @Test
    void unknownToken_staysVisible_andIsReported() {
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                "{{candidate_first_name}} {{salary_offer}}", "body",
                candidate("Anna", "Jensen"), null);

        assertEquals("Anna {{salary_offer}}", rendered.subject());
        assertEquals(Set.of("salary_offer"), rendered.unresolvedFields());
    }

    @Test
    void missingPosition_rendersEmptyTitle_notNull() {
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                "Om {{position_title}}", "body", candidate("Anna", "Jensen"), null);

        assertEquals("Om ", rendered.subject());
        assertTrue(rendered.unresolvedFields().isEmpty());
    }

    @Test
    void toHtml_escapesMarkupAndConvertsNewlines() {
        String html = RecruitmentEmailRenderer.toHtml(
                "Kære <Anna> & venner\nMed \"venlig\" hilsen");

        assertTrue(html.contains("Kære &lt;Anna&gt; &amp; venner<br>"));
        assertTrue(html.contains("Med &quot;venlig&quot; hilsen"));
        assertFalse(html.contains("<Anna>"));
    }

    @Test
    void toHtml_danishCharactersPassThroughUnescaped() {
        String html = RecruitmentEmailRenderer.toHtml("æøå ÆØÅ é");
        assertTrue(html.contains("æøå ÆØÅ é"));
    }

    // ---- P19: caller-supplied extras (the consent link) -----------------

    @Test
    void extras_resolveTheConsentLink_onlyWhenSupplied() {
        String body = "Klik her: {{consent_link}}";

        RecruitmentEmailRenderer.Rendered without = RecruitmentEmailRenderer.render(
                "Emne", body, null, null);
        assertTrue(without.body().contains("{{consent_link}}"),
                "without extras the token stays VISIBLE (manual send cannot mint a token)");
        assertTrue(without.unresolvedFields().contains("consent_link"));

        RecruitmentEmailRenderer.Rendered with = RecruitmentEmailRenderer.render(
                "Emne", body, null, null,
                java.util.Map.of("consent_link", "https://intra.trustworks.dk/consent/abc123"));
        assertTrue(with.body().contains("https://intra.trustworks.dk/consent/abc123"));
        assertTrue(with.unresolvedFields().isEmpty());
    }

    // ---- The send gate: an unfilled link placeholder must never go out ----

    @Test
    void unresolvedLinkTokens_findsTheConsentLinkInEitherFormat() {
        assertEquals(Set.of("consent_link"), RecruitmentEmailRenderer.unresolvedLinkTokens(
                "Emne", "Klik her: {{consent_link}}", RecruitmentEmailBodyFormat.PLAIN));
        assertEquals(Set.of("consent_link"), RecruitmentEmailRenderer.unresolvedLinkTokens(
                "Emne", "<p>Klik her: {{consent_link}}</p>", RecruitmentEmailBodyFormat.HTML));
    }

    @Test
    void unresolvedLinkTokens_findsATokenTheRichEditorSplitAcrossMarkup() {
        // The exact case healSplitTokens exists for: bolding half a token
        // must not be a way past the gate.
        assertEquals(Set.of("consent_link"), RecruitmentEmailRenderer.unresolvedLinkTokens(
                "Emne", "<p>{{consent_<b>link</b>}}</p>", RecruitmentEmailBodyFormat.HTML));
    }

    @Test
    void unresolvedLinkTokens_alsoScansTheSubject() {
        assertEquals(Set.of("consent_link"), RecruitmentEmailRenderer.unresolvedLinkTokens(
                "Dit link: {{consent_link}}", "<p>Ingen tokens her</p>",
                RecruitmentEmailBodyFormat.HTML));
    }

    @Test
    void unresolvedLinkTokens_ignoresCosmeticTokens() {
        // Only *_link is fatal. A missing position title is embarrassing, not
        // broken, and blocking on it would refuse legitimate sends.
        assertTrue(RecruitmentEmailRenderer.unresolvedLinkTokens(
                "Emne", "<p>Kære {{candidate_first_name}} — {{position_title}}</p>",
                RecruitmentEmailBodyFormat.HTML).isEmpty());
    }

    @Test
    void unresolvedLinkTokens_emptyOnceTheLinkIsResolved() {
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                "Emne", "<p>Klik her: {{consent_link}}</p>", null, null,
                java.util.Map.of("consent_link", "https://intra.trustworks.dk/consent/abc123"),
                RecruitmentEmailBodyFormat.HTML);
        assertTrue(RecruitmentEmailRenderer.unresolvedLinkTokens(
                        rendered.subject(), rendered.body(), RecruitmentEmailBodyFormat.HTML)
                .isEmpty(), "the sweep's own send must pass the gate it enforces on humans");
    }

    @Test
    void unresolvedLinkTokens_toleratesNulls() {
        assertTrue(RecruitmentEmailRenderer
                .unresolvedLinkTokens(null, null, null).isEmpty());
    }
}
