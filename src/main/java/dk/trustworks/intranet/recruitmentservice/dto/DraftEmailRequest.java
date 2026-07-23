package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Request an AI-personalised draft of a template for one candidate (P16).
 * {@code templateUuid} is required — the composer personalises an existing
 * Danish template, it does not write from nothing. {@code applicationUuid}
 * is optional position/stage context (must belong to the candidate);
 * {@code instruction} is the recruiter's optional one-liner, e.g.
 * "nævn at vi vender tilbage efter sommerferien".
 * <p>
 * The response is a draft only — sending stays the recruiter's explicit
 * action through {@code POST /candidates/{uuid}/emails/send}.
 */
public record DraftEmailRequest(
        String templateUuid,
        String applicationUuid,
        String instruction
) {
}
