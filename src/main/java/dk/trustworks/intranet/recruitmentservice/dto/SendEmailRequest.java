package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Manual send from the compose dialog (P15). Subject/body arrive FINAL —
 * the recruiter may have edited the rendered template, and the send is
 * verbatim (the P16 AI composer reuses this contract for its
 * recruiter-reviewed drafts). {@code templateUuid} is provenance only.
 */
public record SendEmailRequest(
        String templateUuid,
        String applicationUuid,
        String subject,
        String body
) {
}
