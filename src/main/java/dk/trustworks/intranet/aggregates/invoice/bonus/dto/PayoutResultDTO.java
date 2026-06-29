package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

/**
 * Result of a partner-bonus payout. The sales/production amounts are recomputed server-side
 * (the client cannot dictate them), so the frontend should display these returned values.
 */
public record PayoutResultDTO(
        String userUuid,
        int fiscalYear,
        double salesBonus,
        double productionBonus,
        double groupSalesBasis,
        int partnersInGroup
) {}
