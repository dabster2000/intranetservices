package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;

import java.time.LocalDateTime;

/**
 * One review-queue row (P15) with the rendered snapshot the approver
 * reviews. Candidate name resolved for display; the reason explains WHY
 * the email did not auto-send.
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
        LocalDateTime createdAt
) {
    public static PendingEmailResponse of(RecruitmentPendingEmail pending,
                                          RecruitmentCandidate candidate) {
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
                pending.getCreatedAt());
    }
}
