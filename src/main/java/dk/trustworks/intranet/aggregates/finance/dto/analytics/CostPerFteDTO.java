package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Monthly cost per billable FTE with salary and OPEX breakdown.
 *
 * Per-FTE values are nullable: when billable FTE is zero for a month,
 * division is undefined and these fields are {@code null}.
 */
public record CostPerFteDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        Double salaryPerFteDkk,
        Double opexPerFteDkk,
        double billableFte,
        double totalSalaryDkk,
        double totalOpexDkk
) {}
