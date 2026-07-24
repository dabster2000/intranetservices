package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailCopyResolver;

/**
 * One person the compose dialog may copy on a candidate email.
 * <p>
 * {@code source} is the copy role that produced them (INTERVIEWERS |
 * SENDER | HIRING_OWNER) — the dialog turns it into a human label.
 * {@code selected} reflects the picked template's own policy, so the
 * dialog opens pre-filled with exactly what the template would send and
 * the recruiter only has to intervene when they disagree.
 */
public record CopyRecipientResponse(
        String userUuid,
        String name,
        String email,
        String source,
        boolean selected
) {
    public static CopyRecipientResponse of(RecruitmentEmailCopyResolver.CopyRecipient recipient,
                                           boolean selected) {
        return new CopyRecipientResponse(
                recipient.userUuid(),
                recipient.name(),
                recipient.email(),
                recipient.source() == null ? null : recipient.source().name(),
                selected);
    }
}
