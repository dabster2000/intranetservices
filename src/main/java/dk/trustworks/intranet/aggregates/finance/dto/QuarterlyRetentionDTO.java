package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Backend DTO for Quarterly Client Retention Data
 *
 * Represents retention metrics for a single fiscal quarter.
 * Used by ClientRetentionTrendDTO to build quarterly retention trend chart.
 *
 * Fiscal quarters (Trustworks convention):
 * - Q1 = Jul-Sep
 * - Q2 = Oct-Dec
 * - Q3 = Jan-Mar
 * - Q4 = Apr-Jun
 *
 * Used by CxO Dashboard Client Portfolio Tab (Chart C).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuarterlyRetentionDTO {

    /**
     * Quarter label in format "Q1 2024" or "Q2 FY25"
     * Represents fiscal quarter per Trustworks convention
     */
    private String quarter;

    /**
     * Client retention rate as percentage (0-100)
     * Formula: (retainedClients / previousQuarterClients) * 100
     * Example: 24 retained of 30 = 80.0%
     */
    private double retentionRate;

    /**
     * Count of new clients that appeared in this quarter
     * Definition: Clients with revenue in current quarter but not in previous quarter
     */
    private int newClients;

    /**
     * Count of clients that churned in this quarter
     * Definition: Clients with revenue in previous quarter but not in current quarter
     */
    private int churnedClients;

    /**
     * Count of clients that were retained from previous quarter
     * Definition: Clients with revenue in both previous and current quarter
     */
    private int retainedClients;

    /**
     * Names of new clients in this quarter (sorted alphabetically)
     * Empty list if no new clients
     */
    private List<String> newClientNames;

    /**
     * Names of churned clients in this quarter (sorted alphabetically)
     * Empty list if no churned clients
     */
    private List<String> churnedClientNames;
}
