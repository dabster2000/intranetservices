package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Revenue concentration by client for a team in a fiscal year.
 */
public record TeamClientConcentrationDTO(
        String clientUuid,
        String clientName,
        /** Revenue from this client for the team (DKK) */
        double revenue,
        /** Percentage of total team revenue */
        double revenueSharePercent
) {}
