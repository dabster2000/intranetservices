package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Response body for {@code POST /recruitment/referrals} — deliberately just
 * the identity: the submitter follows progress via
 * {@code GET /recruitment/referrals/mine}, never through a referral detail
 * surface (there is none).
 */
public record ReferralCreateResponse(String uuid) {
}
