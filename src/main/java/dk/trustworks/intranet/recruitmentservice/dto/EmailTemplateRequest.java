package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Create/update body for a candidate-email template (P15). On update,
 * {@code templateKey} is ignored — keys are immutable once created
 * (reporting and EMAIL_SENT events reference them). {@code triggerKey}
 * is the opposite: it is the routing, it is meant to move, and on update
 * an absent/null value CLEARS the assignment rather than keeping the
 * stored one.
 * <p>
 * {@code copyRoles} names the internal people this template copies
 * (INTERVIEWERS | SENDER | HIRING_OWNER; absent or empty = nobody) and
 * {@code copyMode} whether they are invisible (BCC, the default) or
 * visible to the candidate (CC).
 */
public record EmailTemplateRequest(
        String templateKey,
        /** Pipeline moment this letter answers; null/blank = unassigned. */
        String triggerKey,
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
