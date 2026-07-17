package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Result of a teamlead-bonus payout (server-recomputed component amounts + lump sums created). */
@Schema(name = "TeamleadPayoutResult", description = "Result of a teamlead bonus payout")
public record TeamleadPayoutResultDTO(
        double poolAmount,
        double productionAmount,
        double splitAmount,
        double prepaidDeduction,
        double totalAmount,
        int lumpSumsCreated
) {}
