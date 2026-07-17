package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Upsert payload for a member-month inclusion override (the team comes from the path). Upserts on
 * (teamuuid, useruuid, month). Sizes match the V415 column widths.
 */
@Schema(name = "TeamleadMemberOverrideRequest",
        description = "Force a member-month into or out of the teamlead bonus calculation")
public record TeamleadMemberOverrideRequest(
        @NotBlank @Size(max = 36) String useruuid,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "month must be in YYYYMM format") String month,
        @NotNull Boolean included,
        @Size(max = 500) String note
) {}
