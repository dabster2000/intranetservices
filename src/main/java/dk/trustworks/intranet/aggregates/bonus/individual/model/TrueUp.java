package dk.trustworks.intranet.aggregates.bonus.individual.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Year-end true-up settings (enabled only for {@code MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP}).
 *
 * @param enabled          whether a true-up is produced
 * @param formula          currently only {@code FY_EARNED_MINUS_ADVANCES}
 * @param negativeHandling policy for a negative true-up (default {@code WRITE_OFF}, D1)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrueUp(boolean enabled, String formula, NegativeHandling negativeHandling) {
}
