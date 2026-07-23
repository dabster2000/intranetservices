package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope for {@code GET /recruitment/candidates/{uuid}/events}
 * ({@code ICandidateTimeline} in the FE↔BE contract). Events are ordered
 * {@code seq} DESC (newest first); {@code hasMore} is exact — it accounts
 * for event-level filtering, so a {@code false} means the visible stream is
 * exhausted. Clients paginate with {@code beforeSeq} = the smallest
 * {@code seq} on the current page.
 */
public record CandidateTimelineResponse(
        List<TimelineEvent> events,
        boolean hasMore
) {
}
