package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Response of {@code GET /recruitment/slack/flags} (P13, Slack spec §6.1)
 * — the literal Slack companion toggles, booleans only. Mirrors
 * {@link RecruitmentAiFlagsResponse}: the endpoint reports the flags; the
 * guards live where the features execute ({@code interactivity} is
 * enforced by the BFF middleware AND re-checked by the inbound dispatch).
 */
public record RecruitmentSlackFlagsResponse(
        boolean interactivity,
        boolean cards,
        boolean partnerChannels,
        boolean refer,
        boolean triageActions,
        boolean capture,
        boolean lookup,
        boolean scorecard,
        boolean appHome,
        boolean morningBrief,
        boolean dpoDigest,
        boolean assistant
) {
}
