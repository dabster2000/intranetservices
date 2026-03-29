package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Monthly revenue vs salary cost for a team.
 */
public record TeamRevenueCostTrendDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        /** Total registered revenue for the team in this month (DKK) */
        double revenue,
        /** Total salary cost for the team in this month (DKK) */
        double salaryCost,
        /** Revenue minus salary cost */
        double margin
) {}
