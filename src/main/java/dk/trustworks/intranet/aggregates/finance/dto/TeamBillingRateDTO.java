package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Billing rate analysis for a team member: actual effective rate vs break-even rate.
 */
public record TeamBillingRateDTO(
        String userId,
        String firstname,
        String lastname,
        /** Actual effective rate (revenue / billable hours) for the fiscal year; null if no hours */
        Double actualRate,
        /** Break-even rate from fact_minimum_viable_rate; null if not available */
        Double breakEvenRate,
        /** Actual - breakEven; positive means profitable; null if either rate is null */
        Double rateMargin
) {}
