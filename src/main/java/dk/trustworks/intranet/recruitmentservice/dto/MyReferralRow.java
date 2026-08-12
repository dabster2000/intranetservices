package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralDerivedStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralRelation;

import java.time.LocalDateTime;

/**
 * One row of "My referrals" — deliberately minimal (ATS plan §P6): the
 * referrer must never get a handle to the candidate record, so this DTO
 * carries NO candidate uuid, NO position facts and NO stage codes. The
 * milestone-level {@link #derivedStatus} is computed server-side from the
 * candidate/application state on every read.
 *
 * @param uuid              the referral row's uuid for
 *                          {@link MyReferralOrigin#REFERRAL_FORM}; for
 *                          {@link MyReferralOrigin#RECORDED_ON_CANDIDATE}
 *                          there is no referral row, so this is a stable
 *                          synthetic id derived from the candidate uuid by a
 *                          one-way hash — usable as a list key, useless as a
 *                          handle to the candidate.
 * @param referrerRelation  {@code null} for
 *                          {@link MyReferralOrigin#RECORDED_ON_CANDIDATE} —
 *                          nobody filled in the refer form, so there is no
 *                          declared relation. Consumers must tolerate null.
 * @param submittedAt       when the referral was submitted, or — for
 *                          {@link MyReferralOrigin#RECORDED_ON_CANDIDATE} —
 *                          when the candidate was registered.
 */
public record MyReferralRow(
        String uuid,
        String candidateName,
        RecruitmentReferralRelation referrerRelation,
        String externalReferrerName,
        LocalDateTime submittedAt,
        RecruitmentReferralDerivedStatus derivedStatus,
        MyReferralOrigin origin
) {
}
