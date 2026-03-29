package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single consultant's utilization ranking over a trailing 12-month (TTM) window.
 * Used by the Consultant Insights tab to show top/bottom utilization performers.
 *
 * Utilization % = (billableHours / netAvailableHours) × 100
 * Only includes consultants with net available hours > 100 to avoid part-month noise.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultantUtilizationRankingDTO {

    /** User UUID */
    private String userId;

    /** Consultant first name */
    private String firstname;

    /** Consultant last name */
    private String lastname;

    /** Practice code (PM, BA, SA, CYB, DEV) */
    private String practice;

    /** Net utilization percentage: billable / net_available × 100 */
    private double utilizationPercent;

    /** Total billable hours in the TTM window */
    private double billableHours;

    /** Total net available hours in the TTM window */
    private double netAvailableHours;
}
