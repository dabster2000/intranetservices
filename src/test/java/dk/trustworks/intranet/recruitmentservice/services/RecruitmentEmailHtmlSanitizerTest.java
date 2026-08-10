package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allow-list is the boundary between a rich-text template and a
 * candidate's mail client, so these tests pin what survives it — both the
 * formatting recruiters are meant to keep, and the markup they must never be
 * able to ship, however it is smuggled in.
 */
class RecruitmentEmailHtmlSanitizerTest {

    // ---- What must survive ------------------------------------------------

    @Test
    void keepsTheFormattingRecruitersActuallyUse() {
        String clean = RecruitmentEmailHtmlSanitizer.clean(
                "<p>Kære <strong>Søren</strong>,</p>"
                        + "<p>Vi glæder os til at møde dig:</p>"
                        + "<ul><li>Tirsdag</li><li>Onsdag</li></ul>"
                        + "<p><em>Med venlig hilsen</em><br>Trustworks</p>");

        assertTrue(clean.contains("<strong>Søren</strong>"));
        assertTrue(clean.contains("<ul><li>Tirsdag</li>"));
        assertTrue(clean.contains("<em>Med venlig hilsen</em><br>"));
    }

    @Test
    void keepsLinksOnTheThreeProtocolsAMailClientCanOpen() {
        assertTrue(RecruitmentEmailHtmlSanitizer
                .clean("<a href=\"https://trustworks.dk\">os</a>").contains("href=\"https://trustworks.dk\""));
        assertTrue(RecruitmentEmailHtmlSanitizer
                .clean("<a href=\"mailto:hr@trustworks.dk\">skriv</a>").contains("mailto:hr@trustworks.dk"));
    }

    @Test
    void danishCharactersAreNeverEntityEncoded() {
        assertTrue(RecruitmentEmailHtmlSanitizer.clean("<p>æøå ÆØÅ é</p>").contains("æøå ÆØÅ é"));
    }

    @Test
    void mergeTokensPassThroughUntouched() {
        assertTrue(RecruitmentEmailHtmlSanitizer
                .clean("<p>Kære {{candidate_first_name}}</p>").contains("{{candidate_first_name}}"));
    }

    // ---- What must not ----------------------------------------------------

    @Test
    void stripsScriptsAndEventHandlers() {
        String clean = RecruitmentEmailHtmlSanitizer.clean(
                "<p onclick=\"steal()\">Hej<script>alert(1)</script></p>"
                        + "<img src=x onerror=alert(1)>");

        assertFalse(clean.contains("script"));
        assertFalse(clean.contains("onclick"));
        assertFalse(clean.contains("onerror"));
        assertFalse(clean.contains("<img"));
        assertTrue(clean.contains("Hej"));
    }

    @Test
    void dropsJavascriptAndDataUrls() {
        assertFalse(RecruitmentEmailHtmlSanitizer
                .clean("<a href=\"javascript:alert(1)\">klik</a>").contains("javascript"));
        assertFalse(RecruitmentEmailHtmlSanitizer
                .clean("<a href=\"data:text/html;base64,PHNjcmlwdD4=\">klik</a>").contains("data:"));
    }

    @Test
    void dropsStyleClassAndIdBecauseNoMailClientHonoursThem() {
        String clean = RecruitmentEmailHtmlSanitizer.clean(
                "<style>p{display:none}</style>"
                        + "<p style=\"color:red\" class=\"MsoNormal\" id=\"x\">Hej</p>");

        assertFalse(clean.contains("style"));
        assertFalse(clean.contains("class"));
        assertFalse(clean.contains("id="));
        assertTrue(clean.contains("<p>Hej</p>"));
    }

    @Test
    void rebalancesUnclosedMarkup() {
        assertEquals("<p><strong>Hej</strong></p>",
                RecruitmentEmailHtmlSanitizer.clean("<p><strong>Hej"));
    }

    @Test
    void addsNothingSoTheFrontendAndBackendAgree() {
        // Purely subtractive: no rel, no target, no injected attributes. This
        // is what lets a body the editor accepted round-trip unchanged.
        assertEquals("<a href=\"https://trustworks.dk\">os</a>",
                RecruitmentEmailHtmlSanitizer.clean("<a href=\"https://trustworks.dk\">os</a>"));
    }

    @Test
    void isIdempotent() {
        String once = RecruitmentEmailHtmlSanitizer.clean(
                "<p>Kære <b>Søren</b> <a href=\"https://x.dk\">link</a></p>");
        assertEquals(once, RecruitmentEmailHtmlSanitizer.clean(once));
    }

    // ---- Blankness --------------------------------------------------------

    @Test
    void anEmptyRichEditorCountsAsBlank() {
        // What a contentEditable serialises to when the user cleared it —
        // String.isBlank() calls all of these non-empty.
        assertTrue(RecruitmentEmailHtmlSanitizer.isBlankHtml("<p><br></p>"));
        assertTrue(RecruitmentEmailHtmlSanitizer.isBlankHtml("<div><br></div>"));
        assertTrue(RecruitmentEmailHtmlSanitizer.isBlankHtml("<p>   </p>"));
        assertTrue(RecruitmentEmailHtmlSanitizer.isBlankHtml(""));
        assertTrue(RecruitmentEmailHtmlSanitizer.isBlankHtml(null));
        assertFalse(RecruitmentEmailHtmlSanitizer.isBlankHtml("<p>Hej</p>"));
    }

    // ---- Conversions ------------------------------------------------------

    @Test
    void plainToHtml_makesParagraphsFromBlankLines() {
        assertEquals("<p>Kære Anna</p><p>Tak for din ansøgning.</p>",
                RecruitmentEmailHtmlSanitizer.plainToHtml(
                        "Kære Anna\n\nTak for din ansøgning."));
    }

    @Test
    void plainToHtml_makesSingleNewlinesLineBreaks() {
        assertEquals("<p>Med venlig hilsen<br>Trustworks</p>",
                RecruitmentEmailHtmlSanitizer.plainToHtml("Med venlig hilsen\nTrustworks"));
    }

    @Test
    void plainToHtml_escapesSoLegacyTextCannotBecomeMarkup() {
        assertEquals("<p>5 &lt; 6 &amp; R&amp;D</p>",
                RecruitmentEmailHtmlSanitizer.plainToHtml("5 < 6 & R&D"));
    }

    @Test
    void plainToHtml_neverReturnsNothing() {
        assertEquals("<p></p>", RecruitmentEmailHtmlSanitizer.plainToHtml(""));
        assertEquals("<p></p>", RecruitmentEmailHtmlSanitizer.plainToHtml(null));
    }

    @Test
    void toPlainText_keepsTheLineStructureAHumanReads() {
        assertEquals("Kære Anna\n\nTak for din ansøgning.\n\nMed venlig hilsen\nTrustworks",
                RecruitmentEmailHtmlSanitizer.toPlainText(
                        "<p>Kære Anna</p><p>Tak for din ansøgning.</p>"
                                + "<p>Med venlig hilsen<br>Trustworks</p>"));
    }

    @Test
    void toPlainText_unescapesEntitiesRatherThanShowingThem() {
        assertEquals("5 < 6 & R&D", RecruitmentEmailHtmlSanitizer.toPlainText("<p>5 &lt; 6 &amp; R&amp;D</p>"));
    }

    @Test
    void plainTextRoundTripsThroughHtml() {
        String original = "Kære Anna\n\nTak for din ansøgning.\n\nMed venlig hilsen\nTrustworks";
        assertEquals(original, RecruitmentEmailHtmlSanitizer.toPlainText(
                RecruitmentEmailHtmlSanitizer.plainToHtml(original)));
    }
}
