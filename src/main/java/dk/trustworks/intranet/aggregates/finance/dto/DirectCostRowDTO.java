package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single project×month direct delivery cost row for the EBITDA source data export.
 * Sourced from {@code fact_project_financials_mat} at project×month granularity.
 * Used as one row in the "Direct Costs" Excel tab of the EBITDA export.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DirectCostRowDTO {

    /** UUID of the Trustworks legal entity that owns this cost */
    private String companyUuid;

    /** Display name of the Trustworks legal entity */
    private String companyName;

    /** UUID of the project */
    private String projectUuid;

    /** Display name of the project */
    private String projectName;

    /** UUID of the client */
    private String clientUuid;

    /** Display name of the client */
    private String clientName;

    /** Month key in YYYYMM format (e.g., "202407") */
    private String monthKey;

    /** User-friendly month label (e.g., "Jul 2024") */
    private String monthLabel;

    /** Sector identifier (e.g., "PRIVATE", "PUBLIC") */
    private String sectorId;

    /** Service line identifier (e.g., "DEV", "PM") */
    private String serviceLineId;

    /** Contract type identifier (e.g., "PERIOD", "FIXED_PRICE") */
    private String contractTypeId;

    /** Direct delivery cost in DKK for this project×month (from fact_project_financials_mat) */
    private double directDeliveryCostDkk;
}
