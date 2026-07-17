package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Create payload for a leader exclusion. Sizes match the V415 column widths. */
@Schema(name = "TeamleadLeaderExclusionRequest",
        description = "Exclude a leader from a team's teamlead bonus for a fiscal year")
public record TeamleadLeaderExclusionRequest(
        @NotNull Integer fiscalYear,
        @NotBlank @Size(max = 36) String teamId,
        @NotBlank @Size(max = 36) String useruuid,
        @Size(max = 500) String note
) {}
