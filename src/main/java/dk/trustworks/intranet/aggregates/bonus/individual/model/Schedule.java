package dk.trustworks.intranet.aggregates.bonus.individual.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The payout schedule of an individual bonus rule.
 *
 * @param cadence YEARLY | MONTHLY | MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP
 * @param yearly  yearly-cadence settings (used when {@code cadence == YEARLY})
 * @param advance monthly-advance settings (used for MONTHLY / ADVANCE cadences)
 * @param trueUp  year-end true-up settings (used for ADVANCE_PLUS_YEARLY_TRUEUP)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Schedule(Cadence cadence, Yearly yearly, Advance advance, TrueUp trueUp) {
}
