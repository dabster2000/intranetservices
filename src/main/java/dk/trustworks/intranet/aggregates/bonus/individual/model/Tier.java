package dk.trustworks.intranet.aggregates.bonus.individual.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * One marginal tier band: {@code rate} applies only to the slice of the basis between {@code from}
 * (inclusive) and {@code to} (exclusive). A {@code null} {@code to} means "no cap" (open-ended).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Tier(BigDecimal from, BigDecimal to, BigDecimal rate) {
}
