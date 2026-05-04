package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;

import java.time.LocalDateTime;

public record RevisionSummary(
        String uuid,
        int versionNumber,
        RevisionKind kind,
        LocalDateTime createdAt
) {
}
