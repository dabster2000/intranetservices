package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Request body for {@code POST /recruitment/referrals} — the 60-second
 * employee referral form (ATS plan §P6, spec §6.1).
 * <p>
 * {@code referrerRelation} arrives as a string and is parsed explicitly in
 * {@code ReferralService} so an unknown value answers a clean 400 (the
 * module has no active bean-validation extension — every check is explicit,
 * findings §P4). Limits (service-enforced): candidateName 1..200,
 * linkedinUrl ≤500 and must reference linkedin.com, email ≤255 basic shape,
 * externalReferrerName ≤200, whyText 1..2000.
 */
public record ReferralCreateRequest(
        String candidateName,
        String linkedinUrl,
        String email,
        String referrerRelation,
        String externalReferrerName,
        String whyText
) {
}
