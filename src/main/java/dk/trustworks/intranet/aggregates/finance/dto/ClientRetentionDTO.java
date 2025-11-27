package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Client Retention Rate (12-Month) KPI
 *
 * Measures the percentage of clients with revenue in the current 12-month window
 * who also had revenue in the previous 12-month window.
 *
 * Formula: Retention Rate % = (Clients in Both Windows / Clients in Current Window) × 100
 *
 * Used by CxO Dashboard Executive Summary Tab.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientRetentionDTO {

    /**
     * Number of distinct clients with revenue in current 12-month window
     */
    private int currentWindowClientCount;

    /**
     * Number of clients with revenue in both current AND prior 12-month windows (retained)
     */
    private int retainedClientCount;

    /**
     * Current retention percentage
     * Formula: (retainedClientCount / currentWindowClientCount) × 100
     */
    private double currentRetentionPercent;

    /**
     * Number of distinct clients with revenue in prior 12-month window
     */
    private int priorWindowClientCount;

    /**
     * Number of clients retained in prior period comparison
     */
    private int priorRetainedCount;

    /**
     * Prior retention percentage (for period-to-period comparison)
     */
    private double priorRetentionPercent;

    /**
     * Change in retention from prior period (percentage points)
     * Example: 78% → 82% = +4.0 percentage points
     */
    private double retentionChangePct;
}
