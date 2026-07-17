package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusLeaderExclusion;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;

/** Read model for a leader exclusion. */
@Schema(name = "TeamleadLeaderExclusion",
        description = "Admin exclusion of a leader from a team's teamlead bonus for a fiscal year")
public record TeamleadLeaderExclusionDTO(
        String uuid,
        int fiscalYear,
        String teamuuid,
        String useruuid,
        String note,
        LocalDateTime createdAt,
        String createdBy
) {
    public static TeamleadLeaderExclusionDTO fromEntity(TeamleadBonusLeaderExclusion e) {
        return new TeamleadLeaderExclusionDTO(
                e.uuid,
                e.fiscalYear,
                e.teamuuid,
                e.useruuid,
                e.note,
                e.createdAt,
                e.createdBy
        );
    }
}
