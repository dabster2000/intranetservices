package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * The recruitment email settings shown on /recruitment/settings.
 * <p>
 * {@code fromName} is read-only here — it decorates the SES-verified
 * {@code quarkus.mailer.from} address and is deployment configuration
 * ({@code dk.trustworks.recruitment.email.from-name}), not something a
 * recruiter should be able to change between sends.
 *
 * @param replyToFallback     Reply-To for sends with no human actor; empty
 *                            means no Reply-To header at all
 * @param fromName            read-only sender display name
 * @param fromAddress         read-only SES-verified envelope address
 * @param aiVoiceCard         the tone-of-voice card the AI email composer
 *                            writes by; empty means the composer runs with
 *                            no voice guidance
 * @param aiVoiceCardDefault  the built-in card, so the page can offer
 *                            "restore the default text" without a second
 *                            round-trip
 * @param aiVoiceCardIsDefault true while nobody has edited the card — the
 *                            page shows the built-in text unchanged
 */
public record EmailSettingsResponse(
        String replyToFallback,
        String fromName,
        String fromAddress,
        String aiVoiceCard,
        String aiVoiceCardDefault,
        boolean aiVoiceCardIsDefault
) {
}
