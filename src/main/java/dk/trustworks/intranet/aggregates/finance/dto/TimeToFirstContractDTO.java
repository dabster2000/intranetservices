package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO representing the time from a consultant's hire date to their first contract assignment.
 * Used by the Consultant Insights tab to track onboarding velocity.
 *
 * daysToContract is null if the consultant has no contract yet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeToFirstContractDTO {

    /** User UUID */
    private String userId;

    /** Consultant first name */
    private String firstname;

    /** Consultant last name */
    private String lastname;

    /** Practice code (PM, BA, SA, CYB, DEV) */
    private String practice;

    /** Date the consultant was first set to ACTIVE/CONSULTANT status */
    private LocalDate hireDate;

    /** Start date of the consultant's first contract_consultants entry */
    private LocalDate firstContractDate;

    /** Days between hire and first contract. Null if no contract yet. */
    private Integer daysToContract;
}
