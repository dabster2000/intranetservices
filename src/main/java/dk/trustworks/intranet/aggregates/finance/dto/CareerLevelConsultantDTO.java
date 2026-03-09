package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for an individual consultant within a career level drill-down.
 *
 * <p>Used by {@code GET /finance/cxo/career-level-consultants} to show
 * the individual consultants that make up a career level's aggregated cost card.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CareerLevelConsultantDTO {

    /** User UUID. */
    private String uuid;

    /** First name of the consultant. */
    private String firstname;

    /** Last name of the consultant. */
    private String lastname;

    /** Current monthly salary in DKK. */
    private double monthlySalary;

    /** Whether the consultant has given photo consent. */
    private boolean photoconsent;
}
