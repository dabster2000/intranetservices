package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;

import java.util.List;

/**
 * Response envelope for {@code GET /recruitment/positions}: the positions
 * visible to the calling user (after {@code RecruitmentVisibility}
 * filtering), plus the count for list headers.
 */
public record PositionListResponse(List<RecruitmentPosition> positions, long totalCount) {
}
