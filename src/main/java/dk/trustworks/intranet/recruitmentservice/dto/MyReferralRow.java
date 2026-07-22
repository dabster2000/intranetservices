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
 */
public record MyReferralRow(
        String uuid,
        String candidateName,
        RecruitmentReferralRelation referrerRelation,
        String externalReferrerName,
        LocalDateTime submittedAt,
        RecruitmentReferralDerivedStatus derivedStatus
) {
}
