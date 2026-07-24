package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One review-queue row (P15) with the rendered snapshot the approver
 * reviews. Candidate name resolved for display; the reason explains WHY
 * the email did not auto-send.
 * <p>
 * {@code copyRecipients} is the copy list snapshotted at queue time,
 * re-resolved to people (and re-authorized) for display — so the approver
 * sees exactly who else receives this email before pressing Approve, and
 * can change it.
 */
public record PendingEmailResponse(
        String uuid,
        String candidateUuid,
        String candidateName,
        String applicationUuid,
        String templateKey,
        String reason,
        String toEmail,
        String subject,
        String body,
        List<CopyRecipientResponse> copyRecipients,
        String copyMode,
        LocalDateTime createdAt
) {
    public static PendingEmailResponse of(RecruitmentPendingEmail pending,
                                          RecruitmentCandidate candidate) {
        return of(pending, candidate, List.of());
    }

    public static PendingEmailResponse of(RecruitmentPendingEmail pending,
                                          RecruitmentCandidate candidate,
                                          List<CopyRecipientResponse> copyRecipients) {
        String name = candidate == null ? "" :
                ((candidate.getFirstName() == null ? "" : candidate.getFirstName()) + " "
                        + (candidate.getLastName() == null ? "" : candidate.getLastName())).trim();
        return new PendingEmailResponse(
                pending.getUuid(),
                pending.getCandidateUuid(),
                name,
                pending.getApplicationUuid(),
                pending.getTemplateKey(),
                pending.getReason().name(),
                pending.getToEmail(),
                pending.getSubject(),
                pending.getBody(),
                copyRecipients == null ? List.of() : copyRecipients,
                pending.getCopyMode().name(),
                pending.getCreatedAt());
    }
}
