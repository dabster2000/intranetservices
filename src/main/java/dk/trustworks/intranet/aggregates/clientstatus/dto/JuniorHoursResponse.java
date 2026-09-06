package dk.trustworks.intranet.aggregates.clientstatus.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Junior hours on the account manager's own contracts for one month (JK Team 2.0 WP8, D13).
 *
 * <p>One row per (contract, junior) where the junior is a STUDENT and the contract's client
 * has the viewer as account manager — the same ownership rule the Account Manager page uses
 * today. Hours are split by the §4.4.0 discriminator: paid (rate &gt; 0) and 0 kr on a declared
 * zero-rate line, with the list value given away (WP4b, D2). Twelve-month trend, paid vs 0 kr.
 *
 * @param monthKey yyyyMM of the selected month
 */
public record JuniorHoursResponse(
        String monthKey,
        List<JuniorHoursRow> rows,
        List<JuniorHoursTrendPoint> trend,
        double totalHours,
        double paidHours,
        double zeroRateHours,
        double valueGivenAway
) {

    public record JuniorHoursRow(
            String juniorUuid,
            String juniorName,
            String clientUuid,
            String clientName,
            String contractUuid,
            String contractName,
            double hours,
            double paidHours,
            double zeroRateHours,
            double valueGivenAway,
            String pricingModelCode,
            LocalDate rateReviewDate,
            boolean zeroRateLine
    ) {}

    public record JuniorHoursTrendPoint(
            String monthKey,
            double paidHours,
            double zeroRateHours
    ) {}
}
