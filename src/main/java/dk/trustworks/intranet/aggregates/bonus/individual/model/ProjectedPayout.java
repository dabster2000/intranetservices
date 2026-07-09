package dk.trustworks.intranet.aggregates.bonus.individual.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single projected (or already-committed) bonus payout. Layer-1 output of
 * {@code IndividualBonusScheduleService.project()} — nothing here is persisted until the
 * materialisation job writes the corresponding salary_lump_sum.
 *
 * @param month                  day-1 of the payout month
 * @param amount                 gross DKK
 * @param kind                   ADVANCE / MONTHLY / YEARLY / TRUEUP / FINAL_SETTLEMENT
 * @param status                 COMMITTED (a salary_lump_sum exists) or PROJECTED
 * @param sourceReference        stable idempotency id; equals the lump sum's ref once materialised
 * @param estimated              true if the basis for this payout uses forecast, not actuals
 * @param truncatedByTermination true if the schedule was cut here by an early leave
 */
public record ProjectedPayout(
        LocalDate month,
        BigDecimal amount,
        PayoutKind kind,
        PayoutStatus status,
        String sourceReference,
        boolean estimated,
        boolean truncatedByTermination
) {
}
