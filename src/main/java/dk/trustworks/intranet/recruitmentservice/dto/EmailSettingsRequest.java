package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Update the recruitment email settings (/recruitment/settings).
 * An empty {@code replyToFallback} is legal and means "send automatic
 * emails without a Reply-To header" — the pre-V455 behaviour, kept
 * reachable on purpose.
 */
public record EmailSettingsRequest(
        String replyToFallback
) {
}
