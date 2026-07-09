package dk.trustworks.intranet.aggregates.bonus.individual.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Pro-rating knob. When {@code byMonthsEmployedInFy} is true the earned amount is scaled by
 * {@code monthsEmployedInFy / 12}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProRating(boolean byMonthsEmployedInFy) {
}
