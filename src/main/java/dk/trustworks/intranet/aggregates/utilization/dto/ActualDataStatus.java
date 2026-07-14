package dk.trustworks.intranet.aggregates.utilization.dto;

/** Completeness of fact-backed actual metrics relative to the requested completed-day cutoff. */
public enum ActualDataStatus {
    COMPLETE,
    SOURCE_LAGGED,
    UNAVAILABLE
}
