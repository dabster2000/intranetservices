package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralRelation;

import java.time.LocalDateTime;

/**
 * One row of the recruiter triage queue ({@code GET /recruitment/referrals/pending})
 * — the full referral facts. The referrer's display name is deliberately
 * NOT resolved server-side; the UI resolves {@link #referrerUuid} via its
 * employed-users hook (P2 precedent).
 * <p>
 * {@link #aiSuggestions} (P9, contract §6.3): the latest AI triage
 * suggestions for the referral, re-validated at read time; {@code null}
 * when no suggestions exist or the referral-triage toggle is off.
 */
public record PendingReferralRow(
        String uuid,
        String referrerUuid,
        RecruitmentReferralRelation referrerRelation,
        String externalReferrerName,
        String candidateName,
        String linkedinUrl,
        String email,
        String whyText,
        LocalDateTime submittedAt,
        PendingReferralAiSuggestions aiSuggestions
) {
}
