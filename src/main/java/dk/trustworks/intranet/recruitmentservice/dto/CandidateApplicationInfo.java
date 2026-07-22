package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;

/**
 * Compact open-application facts embedded in {@link CandidateSummary} rows
 * — "which pipeline(s) is this candidate in, and where" without a second
 * fetch. Visibility-filtered per viewer: partner-track applications are
 * absent for non-circle viewers ({@code RecruitmentVisibility}).
 */
public record CandidateApplicationInfo(
        String uuid,
        String positionUuid,
        String positionTitle,
        RecruitmentStage stage
) {
}
