package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope for {@code GET /recruitment/candidates/{uuid}/documents}
 * ({@code ICandidateDocuments} in the FE↔BE contract). Newest upload first.
 */
public record CandidateDocumentsResponse(
        List<CandidateDocument> documents
) {
}
