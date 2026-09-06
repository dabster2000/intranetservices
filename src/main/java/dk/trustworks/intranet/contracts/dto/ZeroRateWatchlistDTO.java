package dk.trustworks.intranet.contracts.dto;

import java.time.LocalDate;

/**
 * One zero-rate consultant line approaching (or past) its step-up date — the commercial
 * call to action (JK Team 2.0 WP4b, spec §4.4.3, D2).
 *
 * <p>{@code valueGivenAway} is registered hours on the contract × {@code list_rate}: what
 * the client has received for free so far. Display and reporting only — it is not revenue,
 * not pipeline, and not a discount record.
 *
 * @param daysRemaining days until the review date; negative when it has passed
 * @param overdue       the review date is in the past and the line is still 0 kr
 */
public record ZeroRateWatchlistDTO(
        String consultantLineUuid,
        String contractUuid,
        String contractName,
        String clientUuid,
        String clientName,
        String accountManagerUuid,
        String accountManagerName,
        String juniorUuid,
        String juniorName,
        LocalDate activeFrom,
        LocalDate activeTo,
        LocalDate rateReviewDate,
        long daysRemaining,
        boolean overdue,
        String zeroRateReason,
        String pricingModelCode,
        double listRate,
        double hoursRegistered,
        double valueGivenAway
) {
}
