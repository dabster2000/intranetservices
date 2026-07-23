package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Render a template against a candidate for the compose preview (P15).
 * {@code applicationUuid} is optional position context — merge fields fall
 * back to empty strings without it.
 */
public record RenderEmailRequest(
        String templateUuid,
        String applicationUuid
) {
}
