package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;

/**
 * Compact projection of a candidate for list-page rendering: includes only
 * what the grid needs (name, email, company UUID, template UUID, status, and
 * the most recent dossier-revision metadata). Avoids hydrating dossiers in
 * full for every row.
 *
 * @param uuid               candidate UUID
 * @param name               concatenated {@code firstName + " " + lastName}
 * @param email              candidate's email
 * @param companyUuid        target company UUID
 * @param templateUuid       UUID of the dossier's template (or {@code null}
 *                           if no dossier exists yet)
 * @param status             {@code CandidateStatus} as a string
 * @param latestRevisionKind {@code RevisionKind} of the most recent revision
 *                           or {@code null}
 * @param latestRevisionAt   timestamp of the most recent revision or
 *                           {@code null}
 */
public record CandidateSummary(
        String uuid,
        String name,
        String email,
        String companyUuid,
        String templateUuid,
        String status,
        String latestRevisionKind,
        LocalDateTime latestRevisionAt
) {
}
