package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * List envelope for {@code GET /recruitment/candidates/{uuid}/applications}.
 * Already visibility-filtered: applications on partner-track positions are
 * absent unless the viewer is in the circle ({@code RecruitmentVisibility}).
 */
public record ApplicationListResponse(
        List<ApplicationResponse> applications,
        long totalCount
) {
}
