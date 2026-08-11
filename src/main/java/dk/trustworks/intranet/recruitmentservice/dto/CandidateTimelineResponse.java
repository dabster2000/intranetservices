package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope for {@code GET /recruitment/candidates/{uuid}/events}
 * ({@code ICandidateTimeline} in the FE↔BE contract). Events are ordered
 * {@code seq} DESC (newest first); {@code hasMore} is exact — it accounts
 * for event-level filtering, so a {@code false} means the visible stream is
 * exhausted. Clients paginate with {@code beforeSeq} = the smallest
 * {@code seq} on the current page.
 *
 * @param compTier whether the viewer belongs to the compensation tier for
 *                 this candidate ({@code RecruitmentVisibility.isCompTierFor}).
 *                 The same decision that un-redacts salary pii on the events
 *                 below, reported explicitly so the profile can offer the
 *                 salary-expectation affordance on a candidate that has no
 *                 such note yet — with an empty page there is no redacted
 *                 event to infer it from. It is a capability hint for
 *                 rendering only: every read stays redacted server-side and
 *                 the write is gated again on POST.
 */
public record CandidateTimelineResponse(
        List<TimelineEvent> events,
        boolean hasMore,
        boolean compTier
) {
}
