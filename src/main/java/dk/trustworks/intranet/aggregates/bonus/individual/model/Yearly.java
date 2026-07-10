package dk.trustworks.intranet.aggregates.bonus.individual.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Yearly-cadence settings. {@code payMonthOffsetFromFyEnd} is how many months after FY end (Jun 30)
 * the single yearly payout lands — e.g. 1 → pay in July of FY+1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Yearly(int payMonthOffsetFromFyEnd) {
}
