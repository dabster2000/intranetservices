package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request to trigger a teamlead-bonus payout for one leader in a fiscal year. */
@Schema(name = "TeamleadPayoutRequest", description = "Trigger a teamlead bonus payout")
public record TeamleadPayoutRequest(
        @NotBlank String userUuid,
        @NotNull Integer fiscalYear,
        @NotBlank String payoutMonth
) {}
