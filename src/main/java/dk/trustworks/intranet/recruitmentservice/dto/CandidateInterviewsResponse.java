package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The profile Interviews tab payload (P11): every interview across the
 * candidate's applications the viewer may see, newest scheduled first.
 */
public record CandidateInterviewsResponse(
        List<InterviewResponse> interviews,
        int totalCount
) {
}
