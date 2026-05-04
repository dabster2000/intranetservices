package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Paged-list envelope for {@code GET /recruitment/candidates}. Mirrors the
 * codebase's standard {@code { data, totalCount }} shape so the frontend
 * grid can render server pagination without a bespoke wrapper.
 */
public record CandidateListResponse(
        List<CandidateSummary> data,
        long totalCount
) {
}
