package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusMemberOverride;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;

/** Read model for a member-month inclusion override. */
@Schema(name = "TeamleadMemberOverride",
        description = "Admin override forcing a member-month into or out of the teamlead bonus calculation")
public record TeamleadMemberOverrideDTO(
        String uuid,
        String teamuuid,
        String useruuid,
        String month,
        boolean included,
        String note,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static TeamleadMemberOverrideDTO fromEntity(TeamleadBonusMemberOverride e) {
        return new TeamleadMemberOverrideDTO(
                e.uuid,
                e.teamuuid,
                e.useruuid,
                e.month,
                e.included,
                e.note,
                e.createdAt,
                e.createdBy,
                e.updatedAt,
                e.updatedBy
        );
    }
}
