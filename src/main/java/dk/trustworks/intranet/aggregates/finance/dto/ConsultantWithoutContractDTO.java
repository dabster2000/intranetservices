package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO representing a consultant who has had no active contract for a specified minimum period.
 * Used by the Consultant Insights tab to identify bench consultants.
 *
 * daysSinceContract is -1 if the consultant has never had a contract.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultantWithoutContractDTO {

    /** User UUID */
    private String userId;

    /** Consultant first name */
    private String firstname;

    /** Consultant last name */
    private String lastname;

    /** Practice code (PM, BA, SA, CYB, DEV) */
    private String practice;

    /** End date of the consultant's most recent contract (null if never had one) */
    private LocalDate lastContractEnd;

    /** Days since the last contract ended. -1 if never had a contract. */
    private int daysSinceContract;
}
