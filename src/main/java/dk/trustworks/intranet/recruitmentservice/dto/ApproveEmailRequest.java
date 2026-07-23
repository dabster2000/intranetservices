package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Optional recruiter edits applied at approval (P15). Blank/absent fields
 * keep the queued rendered snapshot.
 */
public record ApproveEmailRequest(
        String subject,
        String body
) {
}
