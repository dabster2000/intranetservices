package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/** Response envelope for {@code GET /recruitment/referrals/mine}. */
public record MyReferralsResponse(List<MyReferralRow> referrals, long totalCount) {
}
