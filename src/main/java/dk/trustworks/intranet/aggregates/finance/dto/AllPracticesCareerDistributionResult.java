package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Response wrapper for the all-practices career distribution endpoint.
 * Contains the career level distribution grouped by track/level plus
 * a flat member list for avatar/name lookup on the frontend.
 */
public record AllPracticesCareerDistributionResult(
        List<TeamCareerDistributionDTO> distribution,
        List<AllPracticesCareerDistributionResult.MemberBasicInfo> members
) {
    public record MemberBasicInfo(String userId, String firstname, String lastname) {}
}
