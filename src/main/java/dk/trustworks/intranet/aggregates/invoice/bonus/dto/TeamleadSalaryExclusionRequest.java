package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Create payload for a salary-exclusion override. Sizes match the V414 column widths. */
@Schema(name = "TeamleadSalaryExclusionRequest", description = "Create a salary-exclusion override")
public record TeamleadSalaryExclusionRequest(
        @NotNull Integer fiscalYear,
        @NotBlank @Size(max = 36) String useruuid,
        @NotBlank @Size(max = 20) String mode,
        @Size(max = 500) String note
) {}
