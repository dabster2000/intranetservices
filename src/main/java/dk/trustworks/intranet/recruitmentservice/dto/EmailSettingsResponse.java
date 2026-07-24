package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * The recruitment email settings shown on /recruitment/settings.
 * <p>
 * {@code fromName} is read-only here — it decorates the SES-verified
 * {@code quarkus.mailer.from} address and is deployment configuration
 * ({@code dk.trustworks.recruitment.email.from-name}), not something a
 * recruiter should be able to change between sends.
 */
public record EmailSettingsResponse(
        String replyToFallback,
        String fromName,
        String fromAddress
) {
}
