package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Single data point for salary-by-band endpoints.
 * Used for both AVG (salary-development) and SUM (total-salary-development) variants.
 *
 * Matches the response shape of the existing BFF routes so frontend hooks
 * require zero changes.
 */
public record SalaryByBandDTO(
        /** Month key in YYYYMM format (e.g., "202601"). */
        String monthKey,
        /** Calendar year. */
        int year,
        /** Calendar month (1-12). */
        int monthNumber,
        /** Display label (e.g., "Jan 2025"). */
        String monthLabel,
        /** Career band name (Junior, Consultant, Senior/Lead, Manager, Partner, C-Level). */
        String careerBand,
        /** Salary value in DKK. AVG for salary-by-band, SUM for total-salary-by-band. */
        double salaryDkk,
        /** Number of consultants in this band/month. */
        int consultantCount
) {}
