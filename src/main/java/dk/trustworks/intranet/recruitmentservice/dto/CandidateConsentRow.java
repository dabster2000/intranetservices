package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;

import java.time.LocalDateTime;

/**
 * One read-only consent row for the P8 profile's GDPR tab
 * ({@code ICandidateConsent} in the FE↔BE contract). Deliberately a DTO,
 * never the entity: the wire shape is exactly these five fields —
 * {@code token_hash} (already {@code @JsonIgnore} on the entity) can never
 * ride along. {@code requestedAt} is the row's creation time.
 */
public record CandidateConsentRow(
        RecruitmentConsentKind kind,
        RecruitmentConsentStatus status,
        LocalDateTime requestedAt,
        LocalDateTime grantedAt,
        LocalDateTime expiresAt
) {
}
