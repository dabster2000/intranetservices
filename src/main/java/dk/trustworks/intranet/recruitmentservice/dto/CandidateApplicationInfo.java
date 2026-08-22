package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;

/**
 * Compact open-application facts embedded in {@link CandidateSummary} rows
 * — "which pipeline(s) is this candidate in, and where" without a second
 * fetch. Visibility-filtered per viewer: partner-track applications are
 * absent for non-circle viewers ({@code RecruitmentVisibility}).
 * <p>
 * Practice identity travels with the position facts (change request
 * 2026-08-22): the grid shows it on the Position/stage cell and the list
 * endpoint filters on it. Both practice fields are null for positions
 * without a practice (PARTNER / STAFF_ROLE tracks allow that).
 */
public record CandidateApplicationInfo(
        String uuid,
        String positionUuid,
        String positionTitle,
        String practiceUuid,
        String practiceName,
        RecruitmentStage stage
) {
}
