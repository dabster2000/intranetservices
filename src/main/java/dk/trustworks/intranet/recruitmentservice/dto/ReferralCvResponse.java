package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * 201 body of {@code POST /recruitment/referrals/{uuid}/cv} — the optional
 * CV an employee attached to their referral.
 * <p>
 * The referrer is told back exactly what was stored (sanitised filename,
 * normalised MIME type, byte size) so the refer form can confirm the
 * attachment without guessing. No S3 key or bucket ever leaves the
 * backend; {@code fileUuid} is the module's own opaque handle.
 */
public record ReferralCvResponse(
        String fileUuid,
        String filename,
        String contentType,
        int sizeBytes
) {
}
