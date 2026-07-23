package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Create/update body for a candidate-email template (P15). On update,
 * {@code templateKey} is ignored — keys are immutable once created
 * (reporting and EMAIL_SENT events reference them).
 */
public record EmailTemplateRequest(
        String templateKey,
        String name,
        String subject,
        String body,
        Boolean autoSend,
        Boolean active
) {
}
