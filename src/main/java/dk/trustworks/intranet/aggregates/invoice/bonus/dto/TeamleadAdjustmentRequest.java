package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Create/update payload for a teamlead bonus adjustment. Sizes match the V414 column widths. */
@Schema(name = "TeamleadAdjustmentRequest", description = "Create/update a teamlead bonus adjustment")
public record TeamleadAdjustmentRequest(
        @NotNull Integer fiscalYear,
        @NotBlank @Size(max = 36) String useruuid,
        @NotBlank @Size(max = 30) String adjustmentType,
        Double amount,
        Double utilOverride,
        @Size(max = 500) String note
) {}
