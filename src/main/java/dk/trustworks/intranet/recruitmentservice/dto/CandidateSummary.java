package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;

import java.time.LocalDateTime;
import java.util.List;

public record CandidateSummary(
        String uuid,
        String name,
        String email,
        String companyUuid,
        String templateUuid,
        CandidateStatus status,
        CandidatePoolStatus poolStatus,
        CandidateSource source,
        List<String> tags,
        RevisionKind latestRevisionKind,
        LocalDateTime latestRevisionAt
) {
}
