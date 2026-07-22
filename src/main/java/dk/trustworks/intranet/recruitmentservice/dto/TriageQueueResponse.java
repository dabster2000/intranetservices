package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/** Response envelope for {@code GET /recruitment/candidates/triage-queue}. */
public record TriageQueueResponse(List<TriageQueueCandidate> candidates, long totalCount) {
}
