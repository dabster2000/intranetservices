package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Active consultant included in utilization calculations for a given month.
 * Used by Executive/CXO dashboards to list which consultants feed the utilization numbers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UtilizationConsultantDTO {

    /** User UUID */
    private String uuid;

    /** First name */
    private String firstname;

    /** Last name */
    private String lastname;

    /** Practice code: PM, BA, CYB, DEV, SA */
    private String practice;
}
