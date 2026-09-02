package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Render draft template text for the communications editor's live preview.
 * <p>
 * Deliberately NOT a template uuid: the point of the preview is to show text
 * the recruiter is still typing, before — and possibly instead of — saving
 * it. Nothing is written and no candidate is touched; the render runs against
 * the invented sample candidate
 * ({@code RecruitmentEmailService.sampleCandidate()}).
 *
 * @param subject     the draft subject line
 * @param body        the draft body, read according to {@code bodyFormat}
 * @param bodyFormat  PLAIN (legacy text) or HTML (rich text); absent = PLAIN
 * @param templateKey which letter is being edited. Context only: every
 *                    template previews against the same sample candidate,
 *                    so this changes no value today. It rides along because
 *                    the merge vocabulary is already template-specific in
 *                    the editor's insert menu, and a preview that could not
 *                    say which letter it is would have to grow the field
 *                    later anyway.
 */
public record PreviewTemplateRequest(
        String subject,
        String body,
        String bodyFormat,
        String templateKey
) {
}
