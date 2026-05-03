package dk.trustworks.intranet.aggregates.finance.dto.analytics;

import java.time.LocalDate;
import java.util.List;

/**
 * Cost-data freshness for the Executive Dashboard banner.
 *
 * The cost layer (`fact_opex_mat`, Career Level Costs, Practices)
 * is derived from `finance_details`, which is reloaded nightly from
 * e-conomics. A given month only becomes complete once the accountant
 * closes that period in e-conomics — typically 1-3 weeks after month-end.
 *
 * This DTO surfaces the latest available expense date so the UI can
 * warn viewers that recent months may be incomplete.
 *
 * @param latestExpenseDate the maximum `expensedate` across all companies
 *                          in `finance_details`, or null if the table is empty
 * @param daysSinceLatest   how many days behind today the latest expense is
 *                          (0 if today, 30 if a month behind)
 * @param perCompany        per-company breakdown of latest expense date
 */
public record CostDataFreshnessDTO(
        LocalDate latestExpenseDate,
        Integer daysSinceLatest,
        List<CompanyFreshness> perCompany
) {
    /**
     * Per-company freshness row.
     *
     * @param companyUuid    company UUID
     * @param companyName    optional human-readable name (joined from `company` table)
     * @param latestExpenseDate latest `expensedate` for this company
     * @param daysSinceLatest how many days behind today this company is
     */
    public record CompanyFreshness(
            String companyUuid,
            String companyName,
            LocalDate latestExpenseDate,
            Integer daysSinceLatest
    ) {}
}
