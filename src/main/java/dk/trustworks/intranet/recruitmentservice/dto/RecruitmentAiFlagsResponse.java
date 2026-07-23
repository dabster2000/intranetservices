package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Response of {@code GET /recruitment/ai/flags} (P9, contract §6.1) — the
 * literal AI companion toggles, booleans only. No admin bypass logic: the
 * endpoint reports the flags; feature guards live on the feature
 * endpoints themselves.
 */
public record RecruitmentAiFlagsResponse(
        boolean intake,
        boolean brief,
        boolean referralTriage,
        boolean emailComposer,
        boolean weeklyFunnelDigest,
        boolean rejectionPatternsDigest
) {
}
