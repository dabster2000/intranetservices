package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Create/update body for a candidate-email template (P15). On update,
 * {@code templateKey} is ignored — keys are immutable once created
 * (reporting and EMAIL_SENT events reference them).
 * <p>
 * {@code copyRoles} names the internal people this template copies
 * (INTERVIEWERS | SENDER | HIRING_OWNER; absent or empty = nobody) and
 * {@code copyMode} whether they are invisible (BCC, the default) or
 * visible to the candidate (CC).
 */
public record EmailTemplateRequest(
        String templateKey,
        String name,
        String subject,
        String body,
        /** PLAIN (legacy text) or HTML (rich text); absent keeps the stored format. */
        String bodyFormat,
        Boolean autoSend,
        Boolean active,
        List<String> copyRoles,
        String copyMode
) {
}
