package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;

/**
 * Response body for the triage endpoint: the referral's new status plus,
 * on the create leg, the freshly minted candidate's uuid ({@code null} on
 * dismiss).
 */
public record ReferralTriageResponse(
        String referralUuid,
        RecruitmentReferralStatus status,
        String candidateUuid
) {
}
