package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Fiscal year revenue for a single team member.
 */
public record TeamRevenuePerMemberDTO(
        String userId,
        String firstname,
        String lastname,
        /** Total registered revenue for the fiscal year (DKK) */
        double revenue,
        /** Total billable hours for the fiscal year */
        double billableHours,
        /** Effective hourly rate: revenue / billableHours; null if no hours */
        Double effectiveRate
) {}
