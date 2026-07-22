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
        LocalDateTime latestRevisionAt,
        /**
         * Open (non-terminal) applications, visibility-filtered per viewer:
         * partner-track applications are absent for non-circle viewers.
         * Empty when the candidate is in no pipeline (P4).
         */
        List<CandidateApplicationInfo> activeApplications
) {
}
