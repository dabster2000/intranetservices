package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;

/**
 * One-line summary of a dossier revision, embedded in
 * {@link CandidateResponse} so the candidate detail page can render the
 * timeline indicator without loading the full revision body.
 *
 * @param uuid          revision UUID
 * @param versionNumber 1-based version per dossier
 * @param kind          {@code RevisionKind} as a string
 * @param createdAt     when the revision was allocated
 */
public record RevisionSummary(
        String uuid,
        int versionNumber,
        String kind,
        LocalDateTime createdAt
) {
}
