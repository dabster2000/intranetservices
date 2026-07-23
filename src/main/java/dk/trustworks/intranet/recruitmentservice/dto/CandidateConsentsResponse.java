package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope for {@code GET /recruitment/candidates/{uuid}/consents}
 * ({@code ICandidateConsents} in the FE↔BE contract). Newest request first.
 */
public record CandidateConsentsResponse(
        List<CandidateConsentRow> consents
) {
}
