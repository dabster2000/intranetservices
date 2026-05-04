package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;

import java.time.LocalDateTime;

public record CandidateSummary(
        String uuid,
        String name,
        String email,
        String companyUuid,
        String templateUuid,
        CandidateStatus status,
        RevisionKind latestRevisionKind,
        LocalDateTime latestRevisionAt
) {
}
