package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Update the recruitment email settings (/recruitment/settings).
 *
 * <p><b>Null means "leave unchanged", empty means "clear".</b> The page
 * has two independent forms writing to this one endpoint — sender/replies
 * and the AI tone-of-voice card — and each submits only its own field.
 * Treating an absent field as a blanking instruction would let saving the
 * voice card silently wipe the Reply-To address, and vice versa.
 *
 * <p>An empty {@code replyToFallback} is legal and means "send automatic
 * emails without a Reply-To header" — the pre-V455 behaviour, kept
 * reachable on purpose. An empty {@code aiVoiceCard} is legal too and
 * means "let the AI composer write without voice guidance".
 */
public record EmailSettingsRequest(
        String replyToFallback,
        String aiVoiceCard
) {
}
