package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for creating a partner-bonus payout.
 *
 * <p>{@code salesAmount} and {@code productionAmount} are <strong>deprecated and ignored</strong> —
 * payout amounts are recomputed server-side from the un-consumed APPROVED invoices so the client
 * cannot dictate money. They are retained only so older clients that still send them deserialize
 * cleanly. Do not plumb them into the service.</p>
 */
public record PayoutRequestDTO(
        @NotBlank String userUuid,
        double salesAmount,
        double productionAmount,
        @NotBlank String payoutMonth,
        @Min(2000) @Max(2099) int fiscalYear
) {}
