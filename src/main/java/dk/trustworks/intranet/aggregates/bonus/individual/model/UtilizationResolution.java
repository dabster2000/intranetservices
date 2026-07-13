package dk.trustworks.intranet.aggregates.bonus.individual.model;

import java.math.BigDecimal;

/** Numerator, denominator, exact ratio, and finality evidence from one utilization query. */
public record UtilizationResolution(
        BigDecimal billableHours,
        BigDecimal availableHours,
        BigDecimal rawUtilization,
        FactCoverage coverage
) {
}
