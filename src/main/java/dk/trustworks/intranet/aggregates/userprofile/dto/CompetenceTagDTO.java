package dk.trustworks.intranet.aggregates.userprofile.dto;

import dk.trustworks.intranet.aggregates.userprofile.model.UserCompetenceTag;

import java.time.LocalDateTime;

/** One competence tag with its provenance — {@code MANUAL} or {@code CV_TOOL}. */
public record CompetenceTagDTO(String tag, String source, LocalDateTime createdAt) {
    public static CompetenceTagDTO from(UserCompetenceTag row) {
        return new CompetenceTagDTO(row.getTag(), row.getSource().name(), row.getCreatedAt());
    }
}
