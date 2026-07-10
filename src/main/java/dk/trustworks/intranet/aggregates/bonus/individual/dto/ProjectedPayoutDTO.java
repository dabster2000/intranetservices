package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-time projection of a single (committed or projected) payout, for salary "expected pay" views.
 *
 * @param month                  day-1 of the payout month
 * @param amount                 gross DKK
 * @param kind                   ADVANCE / MONTHLY / YEARLY / TRUEUP / FINAL_SETTLEMENT
 * @param status                 COMMITTED or PROJECTED
 * @param sourceReference        stable idempotency id
 * @param estimated              basis uses forecast, not actuals
 * @param truncatedByTermination schedule was cut here by an early leave
 */
public record ProjectedPayoutDTO(
        LocalDate month,
        BigDecimal amount,
        String kind,
        String status,
        String sourceReference,
        boolean estimated,
        boolean truncatedByTermination
) {
}
