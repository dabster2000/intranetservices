package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusAdjustment;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;

/** Read model for an admin-entered teamlead bonus adjustment. */
@Schema(name = "TeamleadBonusAdjustment", description = "Admin-entered teamlead bonus adjustment")
public record TeamleadBonusAdjustmentDTO(
        String uuid,
        int fiscalYear,
        String useruuid,
        String adjustmentType,
        Double amount,
        Double utilOverride,
        String note,
        String createdBy,
        LocalDateTime createdAt
) {
    public static TeamleadBonusAdjustmentDTO fromEntity(TeamleadBonusAdjustment e) {
        return new TeamleadBonusAdjustmentDTO(
                e.uuid,
                e.fiscalYear,
                e.useruuid,
                e.adjustmentType != null ? e.adjustmentType.name() : null,
                e.amount,
                e.utilOverride,
                e.note,
                e.createdBy,
                e.createdAt
        );
    }
}
