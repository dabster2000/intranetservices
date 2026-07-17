package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusSalaryExclusion;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Read model for a salary-exclusion override. */
@Schema(name = "TeamleadBonusSalaryExclusion", description = "Admin salary-exclusion override for the pool basis")
public record TeamleadBonusSalaryExclusionDTO(
        String uuid,
        int fiscalYear,
        String useruuid,
        String mode,
        String note
) {
    public static TeamleadBonusSalaryExclusionDTO fromEntity(TeamleadBonusSalaryExclusion e) {
        return new TeamleadBonusSalaryExclusionDTO(
                e.uuid,
                e.fiscalYear,
                e.useruuid,
                e.mode != null ? e.mode.name() : null,
                e.note
        );
    }
}
