package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/** Response envelope for {@code GET /recruitment/referrals/pending}. */
public record PendingReferralsResponse(List<PendingReferralRow> referrals, long totalCount) {
}
