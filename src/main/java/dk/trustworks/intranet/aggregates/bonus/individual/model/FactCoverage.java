package dk.trustworks.intranet.aggregates.bonus.individual.model;

import java.time.LocalDateTime;

/** Completeness metadata for the daily fact rows used by a monthly calculation. */
public record FactCoverage(
        int expectedRows,
        int actualRows,
        int duplicateRows,
        int nullInputRows,
        LocalDateTime factsAsOf
) {
    public boolean complete() {
        return expectedRows == actualRows
                && duplicateRows == 0
                && nullInputRows == 0
                && (expectedRows == 0 || factsAsOf != null);
    }
}
