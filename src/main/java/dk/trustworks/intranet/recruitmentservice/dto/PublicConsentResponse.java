package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.services.RecruitmentConsentService;

import java.time.LocalDateTime;

/**
 * What the public {@code /consent/[token]} page gets to see (ATS P19) —
 * deliberately minimal: the candidate's first name (the page greets the
 * person the link was mailed to; the token itself is the credential),
 * the consent's kind/status and the dates the Danish copy renders.
 * Never the candidate uuid, email, or anything else.
 */
public record PublicConsentResponse(
        String firstName,
        String kind,
        String status,
        LocalDateTime grantedAt,
        LocalDateTime expiresAt,
        /** When the data is deleted unless consent is (re-)granted; null = no clock. */
        LocalDateTime retentionDeadline) {

    public static PublicConsentResponse from(RecruitmentConsentService.ConsentView view) {
        return new PublicConsentResponse(
                view.candidateFirstName(),
                view.kind().name(),
                view.status().name(),
                view.grantedAt(),
                view.expiresAt(),
                view.retentionDeadline());
    }
}
