package dk.trustworks.intranet.aggregates.jkdashboard.dto;

/**
 * Confidence level for merge detection.
 * Based on how much of the JK's registered hours the surplus covers.
 */
public enum MergeConfidence {
    /** surplus >= 0.8 x jk_registered_hours */
    HIGH,
    /** surplus >= 0.3 x jk_registered_hours */
    MEDIUM,
    /** surplus < 0.3 x jk_registered_hours */
    LOW
}
