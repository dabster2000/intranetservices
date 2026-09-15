package dk.trustworks.intranet.aggregates.conference.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.conference.dto.UnsubscribeFooter;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import jakarta.ws.rs.BadRequestException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ConferenceMailRendererTest {
    private static final String URL = "https://trustworks.dk/unsubscribe#token=" + "a".repeat(43);
    private static final String BODY = "<!doctype html><html><head><style>p{margin:0}</style></head><body><table><tr><td>Hello [name]</td></tr></table></body></html>";

    @Test void insertsExactlyOneFooterInsideBodyOutsideAuthorContainers() {
        String source = BODY.replace("<table>", "<table style='display:none'>");
        var result = Jsoup.parse(ConferenceMailRenderer.render(source, null, "A & <B>", URL));
        var footer = result.select("[data-conference-footer]");
        assertEquals(1, footer.size());
        assertEquals(result.body(), footer.first().parent());
        assertEquals(URL, footer.select("a").attr("href"));
        assertTrue(footer.text().contains("A & <B>"));
        assertEquals(2, result.select("table").size());
        assertFalse(result.select("body > table").first().text().contains("Unsubscribe"));
    }
    @Test void removesOnlyExactLegacyDestinationsAndPreservesSurroundingContent() {
        String body = "<p>Hello <a href='{{preferences_url}}'>Preferences</a> there</p>"
                + "<p><a href='{{unsubscribe_url}}'>Old</a></p><a href='https://example.com/unsubscribe'>Other</a>";
        var result = Jsoup.parse(ConferenceMailRenderer.render(body, null, "List", URL));
        assertEquals(2, result.select("a").size());
        assertTrue(result.text().contains("Hello there"));
        assertEquals(1, result.select("a[href=https://example.com/unsubscribe]").size());
        assertFalse(result.outerHtml().contains("{{"));
    }
    @Test void hiddenBodyAndHostileGlobalCssAreRejected() {
        for (String html : new String[] {
                "<body style='display:none'><p>Hello</p></body>",
                "<style>body{display:none!important}</style><p>Hello</p>",
                "<style>table *{max-height:0!important;overflow:hidden}</style><p>Hello</p>",
                "<style>td{position:absolute;left:-9000px}</style><p>Hello</p>",
                "<body hidden><p>Hello</p></body>" })
            assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.render(html, null, "List", URL));
    }
    @Test void globalBackgroundSpacingAndLineHeightCannotHideManagedFooter() {
        var result = Jsoup.parse(ConferenceMailRenderer.render(
                "<style>table{background-color:#1f2937!important;border-spacing:999999px!important}"
                + "p,a{line-height:0!important;font-size:0!important}</style><p>Hello</p>", null, "List", URL));
        assertTrue(result.select("table[data-conference-footer]").attr("style").contains("border-spacing:0!important"));
        assertTrue(result.select("table[data-conference-footer] td").attr("style").contains("background-color:#ffffff!important"));
        assertTrue(result.select("table[data-conference-footer] a").attr("style").contains("line-height:1.5!important"));
        assertTrue(result.select("table[data-conference-footer] a").attr("style").contains("font:14px Arial,sans-serif!important"));
    }
    @Test void authorOverlayCannotCoverTheFooter() {
        for (String html : new String[] {
                "<div style='position:fixed;inset:0;background:white'>Overlay</div><p>Hello</p>",
                "<style>.overlay{position:absolute;top:100%;z-index:999}</style><div class='overlay'>Overlay</div>",
                "<div style='margin-bottom:-10000px'>Overlay</div>",
                "<div style='box-shadow:0 0 0 100000px white'>Overlay</div>" })
            assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.render(html, null, "List", URL));
    }
    @Test void svgMathAndJavascriptDestinationsDoNotSurviveFinalization() {
        var result = Jsoup.parse(ConferenceMailRenderer.render("<p>Hello</p>"
                + "<svg><a href='javascript:alert(1)'>Click</a><animate attributeName='href'/></svg>"
                + "<math><mtext>Other</mtext></math><a href='java&#10;script:alert(1)'>Unsafe</a>", null, "List", URL));
        assertTrue(result.select("svg,math,animate").isEmpty());
        assertFalse(result.outerHtml().contains("javascript:"));
        assertEquals(1, result.select("a[href]").size());
    }
    @Test void outlookConditionalMarkupCannotHideFooter() {
        assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.render(
                "<!--[if mso]><style>body{display:none}</style><![endif]--><p>Hello</p>", null, "List", URL));
        var result = ConferenceMailRenderer.render("<!--[if mso]><xml><o:OfficeDocumentSettings><o:AllowPNG/>"
                + "<o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]--><p>Hello</p>", null, "List", URL);
        assertTrue(result.contains("o:PixelsPerInch"));
    }
    @Test void actualDesignerResetStylesAndResponsiveLayoutRemainSupported() {
        String css = "html,body{margin:0!important;padding:0!important;width:100%!important;}"
                + "table,td{mso-table-lspace:0pt!important;mso-table-rspace:0pt!important;}"
                + "img{border:0;outline:none;text-decoration:none;}a[x-apple-data-detectors]{color:inherit!important;text-decoration:none!important;}"
                + "@media only screen and (max-width:640px){.email-shell{width:100%!important;}.stack-column{display:block!important;width:100%!important;max-width:100%!important;}}";
        var result = ConferenceMailRenderer.render("<style>" + css + "</style><body style='margin:0;padding:0;background-color:#edf0f5;'>"
                + "<table class='email-shell'><tr><td class='stack-column'>Hello</td></tr></table></body>", null, "List", URL);
        assertTrue(result.contains("mso-table-lspace:0pt!important"));
        assertEquals(2, Jsoup.parse(result).select("table").size());
    }
    @Test void arbitraryFooterMarkersDoNotDeleteUnrelatedContent() {
        var result = Jsoup.parse(ConferenceMailRenderer.render(
                "<div data-conference-footer='true'><p>Important event details</p></div>", null, "List", URL));
        assertTrue(result.body().text().contains("Important event details"));
        assertEquals(1, result.select("table[data-conference-footer]").size());
    }
    @Test void normalResponsiveTableClassesRemainAvailable() {
        var result = ConferenceMailRenderer.render("<style>body{margin:0;padding:0}table{border-collapse:collapse}"
                + "@media screen and (max-width:600px){.hero{display:none}}</style><table class='hero'><tr><td>Hello</td></tr></table>", null, "List", URL);
        assertTrue(result.contains(".hero{display:none}"));
        assertTrue(result.contains("Hello"));
    }
    @Test void tokenInCommentOrProseNeverSatisfiesRequiredAction() {
        assertThrows(IllegalStateException.class, () -> ConferenceMailRenderer.render(
                "<p>Hello</p><!-- {{unsubscribe_url}} -->", null, "List", URL));
    }
    @Test void footerOnlyBodyIsInvalid() {
        assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.validateContent("Subject", "<a href='{{unsubscribe_url}}'>Unsubscribe</a>"));
        assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.validateContent("Subject", "<!--hello--><style>p{color:red}</style>"));
        assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.validateContent("Subject", "<div style='display:none'>Hidden preheader</div><a href='{{unsubscribe_url}}'>Unsubscribe</a>"));
    }
    @Test void descriptorRejectsUrlInjectionHiddenAppearanceHtmlAndControls() {
        assertThrows(BadRequestException.class, () -> new UnsubscribeFooter("link", "Unsubscribe", "text-link", "centre"));
        assertThrows(BadRequestException.class, () -> new UnsubscribeFooter("conference-unsubscribe", "<img>", "text-link", "centre"));
        assertThrows(BadRequestException.class, () -> new UnsubscribeFooter("conference-unsubscribe", "Unsubscribe\r\n", "text-link", "centre"));
        assertThrows(BadRequestException.class, () -> new UnsubscribeFooter("conference-unsubscribe", "Unsubscribe", "hidden", "centre"));
    }
    @Test void descriptorRejectsUnknownDestinationEvenWhenMapperIgnoresUnknownFields() {
        ObjectMapper mapper = new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"action\":\"conference-unsubscribe\",\"label\":\"Unsubscribe\",\"appearance\":\"text-link\",\"alignment\":\"centre\",\"url\":\"https://evil.example\"}", UnsubscribeFooter.class));
    }
    @Test void saveRejectsLiveOrDemoFootersInSourceBody() {
        assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.validateContent("Subject", "<p>Hello</p><a href='" + URL + "'>Unsubscribe</a>"));
        assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.validateContent("Subject", "<p>Hello</p><a href='#unsubscribe-preview'>Demo</a>"));
    }
    @Test void safelyRendersSupportedLabelAndAppearance() {
        var descriptor = new UnsubscribeFooter("conference-unsubscribe", "Afmeld & stop", "outlined-button", "left");
        var result = Jsoup.parse(ConferenceMailRenderer.render(BODY, descriptor, "List", URL));
        assertEquals("Afmeld & stop", result.select("[data-conference-footer] a").text());
        assertEquals("left", result.select("[data-conference-footer] td").attr("align"));
        assertTrue(result.select("[data-conference-footer] a").attr("style").contains("border:"));
    }
    @Test void retriesAndRecipientsStartFromImmutableSource() {
        String first = ConferenceMailRenderer.render(BODY, null, "List", URL);
        String second = ConferenceMailRenderer.render(BODY, null, "List", URL.replace("a".repeat(43), "b".repeat(43)));
        assertFalse(BODY.contains("token="));
        assertFalse(second.contains(URL));
        assertEquals(1, Jsoup.parse(first).select("[data-conference-footer]").size());
        assertThrows(BadRequestException.class, () -> ConferenceMailRenderer.render(first, null, "List", URL));
    }
    @Test void untrustedLinkOriginsAndPathsFailClosed() {
        assertThrows(IllegalStateException.class, () -> ConferenceMailRenderer.render(BODY, null, "List", "javascript:alert(1)"));
        assertThrows(IllegalStateException.class, () -> ConferenceMailRenderer.render(BODY, null, "List", URL.replace("/unsubscribe", "/elsewhere")));
    }
    @Test void personalizationEscapesName() {
        assertEquals("<p>&lt;img&gt;</p>", ConferenceMailRenderer.personalize("<p>[name]</p>", "<img>"));
    }
    @Test void phaseDescriptorRecomputedAndLegacyReplacementClearsIt() throws Exception {
        ConferenceMailRenderer renderer = new ConferenceMailRenderer(); renderer.json = new ObjectMapper();
        ConferencePhase phase = new ConferencePhase(); phase.setUseMail(true); phase.setSubject("Subject");
        phase.setMail(Base64.getEncoder().encodeToString(BODY.getBytes(StandardCharsets.UTF_8)));
        phase.setMailJson("{\"rows\":[{\"managedFooter\":{\"action\":\"conference-unsubscribe\",\"label\":\"Afmeld\",\"appearance\":\"outlined-button\",\"alignment\":\"left\"}}]}");
        renderer.preparePhase(phase);
        assertEquals("Afmeld", phase.getUnsubscribeFooter().label());
        phase.setMailJson(null); renderer.preparePhase(phase);
        assertNull(phase.getUnsubscribeFooter());
    }
    @Test void mismatchedDesignerDescriptorRejected() {
        ConferenceMailRenderer renderer = new ConferenceMailRenderer(); renderer.json = new ObjectMapper();
        ConferencePhase phase = new ConferencePhase();
        phase.setMailJson("{\"rows\":[{\"managedFooter\":{\"action\":\"conference-unsubscribe\",\"label\":\"Afmeld\",\"appearance\":\"text-link\",\"alignment\":\"centre\"}}]}");
        phase.setUnsubscribeFooter(UnsubscribeFooter.defaults());
        assertThrows(BadRequestException.class, () -> renderer.preparePhase(phase));
    }
}
