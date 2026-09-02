package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * The bytes of a referral's attached CV plus the two headers the recruiter
 * side needs to render them ({@code GET /recruitment/referrals/{uuid}/cv}).
 * <p>
 * Deliberately mirrors the candidate-document download shape so the triage
 * queue's CV control and the P8 Documents tab behave identically: the
 * resource serves it as {@code Content-Disposition: attachment} with the
 * sanitised filename, and the frontend's preview modal frames the same
 * bytes when — and only when — its own allowlist calls them a PDF.
 */
public record ReferralCvDownload(
        byte[] bytes,
        String contentType,
        String filename
) {
}
