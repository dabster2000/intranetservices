package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * List envelope for {@code GET /recruitment/candidates/{uuid}/applications}.
 * Already visibility-filtered: applications on partner-track positions are
 * absent unless the viewer is in the circle ({@code RecruitmentVisibility}).
 */
public record ApplicationListResponse(
        List<ApplicationResponse> applications,
        long totalCount,
        /**
         * Candidate-scoped authoritative capability for reading the offer and
         * contract dossier. It is present even when {@link #applications} is
         * empty, so the UI never needs to reconstruct dossier policy from
         * roles or infer it from an application row.
         */
        boolean viewerCanReadDossier
) {
}
