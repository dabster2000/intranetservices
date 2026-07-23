package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The caller's own interviews (P11): upcoming and recent, soonest first.
 * Cancelled interviews are excluded — nothing to prepare for.
 */
public record MyInterviewsResponse(
        List<MyInterviewRow> interviews,
        int totalCount
) {
}
