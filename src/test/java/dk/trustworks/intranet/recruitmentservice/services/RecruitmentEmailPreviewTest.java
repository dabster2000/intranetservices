package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The communications editor's live preview and the subject of a test send.
 * <p>
 * The load-bearing claim is the first test: a preview is worth nothing if it
 * can disagree with the send. Both go through
 * {@link RecruitmentEmailService#render}, so the assertion is not "these two
 * strings happen to match today" but "there is one rendering path" — and it
 * fails the moment someone reaches for the renderer directly on either side.
 * <p>
 * Database-free: preview writes nothing and reads nothing but the injected
 * settings bean.
 */
class RecruitmentEmailPreviewTest {

    private static final String ADDRESS = "Hausergade 3, 1128 København K";

    private RecruitmentEmailService service;

    @BeforeEach
    void setUp() {
        RecruitmentVisitingAddress addressSetting = mock(RecruitmentVisitingAddress.class);
        when(addressSetting.effectiveAddress()).thenReturn(ADDRESS);
        service = new RecruitmentEmailService();
        service.visitingAddress = addressSetting;
        // No X-Requested-By: recruiter_* resolve to empty, which keeps this
        // test off Panache entirely.
        service.requestHeaderHolder = new RequestHeaderHolder();
    }

    // ---- Preview == send ---------------------------------------------------

    @Test
    void preview_rendersIdenticallyToTheSendPath_forTheSameInput() {
        String subject = "Tak for din ansøgning, {{candidate_first_name}}";
        String body = "Hej {{candidate_full_name}}\n\nTak for din ansøgning til "
                + "{{position_title}} hos {{company_name}}. Vi holder til på "
                + "{{visiting_address}}.";

        RecruitmentEmailRenderer.Rendered sent = service.render(
                template(subject, body, RecruitmentEmailBodyFormat.PLAIN),
                RecruitmentEmailService.sampleCandidate(),
                RecruitmentEmailService.samplePosition());
        RecruitmentEmailRenderer.Rendered previewed =
                service.preview(subject, body, RecruitmentEmailBodyFormat.PLAIN);

        assertEquals(sent.subject(), previewed.subject());
        assertEquals(sent.body(), previewed.body());
        assertEquals(sent.unresolvedFields(), previewed.unresolvedFields());
    }

    @Test
    void preview_rendersIdenticallyToTheSendPath_forARichTextBody() {
        // The HTML path escapes merge values as they land rather than the
        // body wholesale, so it is a genuinely different branch and has to
        // be pinned separately.
        String subject = "Invitation";
        String body = "<p>Hej <strong>{{candidate_first_name}}</strong></p>"
                + "<p>Vi ses på {{visiting_address}}.</p>";

        RecruitmentEmailRenderer.Rendered sent = service.render(
                template(subject, body, RecruitmentEmailBodyFormat.HTML),
                RecruitmentEmailService.sampleCandidate(),
                RecruitmentEmailService.samplePosition());
        RecruitmentEmailRenderer.Rendered previewed =
                service.preview(subject, body, RecruitmentEmailBodyFormat.HTML);

        assertEquals(sent.subject(), previewed.subject());
        assertEquals(sent.body(), previewed.body());
    }

    // ---- The sample candidate ----------------------------------------------

    @Test
    void preview_rendersAgainstTheSampleCandidate_andNeverARealOne() {
        RecruitmentEmailRenderer.Rendered rendered = service.preview(
                "{{candidate_first_name}}",
                "{{candidate_full_name}} — {{candidate_last_name}} — {{position_title}}",
                RecruitmentEmailBodyFormat.PLAIN);

        assertEquals("Anna", rendered.subject());
        assertEquals("Anna Jensen — Jensen — Senior Consultant", rendered.body());
    }

    @Test
    void preview_leavesTheConsentLinkUnresolved_onPurpose() {
        // Only the GDPR sweep can mint a token, so the literal braces ARE
        // what a real send of that letter would produce. Showing anything
        // else would be a preview of an email nobody can send.
        RecruitmentEmailRenderer.Rendered rendered = service.preview(
                "Fornyelse", "Bekræft her: {{consent_link}}",
                RecruitmentEmailBodyFormat.PLAIN);

        assertEquals("Bekræft her: {{consent_link}}", rendered.body());
        assertTrue(rendered.unresolvedFields().contains("consent_link"));
    }

    @Test
    void preview_showsWhatWouldBeSTORED_notWhatWasTyped() {
        // The same sanitizer createTemplate applies on the way into the
        // database. Previewing the raw draft would show the author markup
        // that is about to be thrown away.
        RecruitmentEmailRenderer.Rendered rendered = service.preview(
                "Hej", "<p>Hej <script>alert(1)</script><h1>stort</h1></p>",
                RecruitmentEmailBodyFormat.HTML);

        assertFalse(rendered.body().contains("script"), rendered.body());
        assertFalse(rendered.body().contains("<h1"), rendered.body());
    }

    // ---- The test send's subject -------------------------------------------

    @Test
    void aTestSubject_isPrefixed() {
        assertEquals("[TEST] Tak for din ansøgning",
                RecruitmentEmailService.testSubject("Tak for din ansøgning"));
    }

    @Test
    void aTestSubject_isCappedAtTheColumnWidth() {
        // mail.subject is VARCHAR(300) and a template subject may use all
        // 300, so the prefix alone can overflow the column and fail the
        // insert. Truncation beats a 500.
        String maximal = "x".repeat(RecruitmentEmailService.SUBJECT_MAX_LENGTH);

        String prefixed = RecruitmentEmailService.testSubject(maximal);

        assertEquals(RecruitmentEmailService.SUBJECT_MAX_LENGTH, prefixed.length());
        assertTrue(prefixed.startsWith(RecruitmentEmailService.TEST_SUBJECT_PREFIX));
    }

    private static RecruitmentEmailTemplate template(String subject, String body,
                                                     RecruitmentEmailBodyFormat format) {
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setSubject(subject);
        template.setBody(RecruitmentEmailService.sanitizeBodyForStorage(body, format));
        template.setBodyFormat(format);
        return template;
    }
}
