package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Time-to-first-contract data scoped to a team, with a company-wide average for comparison.
 *
 * <p>Each team member entry shows their hire date, first contract date, and days between.
 * The {@code companyAverageDays} provides a reference benchmark.
 */
public record TeamTimeToFirstContractDTO(
        /** Per-member time-to-first-contract data */
        List<MemberTimeToContract> teamMembers,
        /** Company-wide average days to first contract (across all consultants with contracts) */
        Double companyAverageDays
) {

    /**
     * Individual team member's time-to-first-contract entry.
     */
    public record MemberTimeToContract(
            String userId,
            String firstname,
            String lastname,
            LocalDate hireDate,
            LocalDate firstContractDate,
            /** Days between hire and first contract. Null if no contract yet. */
            Integer daysToContract
    ) {}
}
