package dk.trustworks.intranet.aggregates.accounting.dto;

import dk.trustworks.intranet.financeservice.model.enums.CostType;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for PATCH /accounting/accounts/{uuid}/cost-type
 */
public record UpdateCostTypeRequest(
        @NotNull(message = "costType is required") CostType costType
) {}
