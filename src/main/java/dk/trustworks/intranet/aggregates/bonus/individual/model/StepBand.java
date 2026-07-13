package dk.trustworks.intranet.aggregates.bonus.individual.model;

import java.math.BigDecimal;

/** A fixed supplement selected by an inclusive-from/exclusive-to utilization interval. */
public record StepBand(BigDecimal from, BigDecimal to, BigDecimal amount) {
}
