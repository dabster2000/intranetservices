package dk.trustworks.intranet.aggregates.finance.dto.analytics;

import java.time.LocalDate;
import java.util.List;

/**
 * Cost-data freshness for the Executive Dashboard banner.
 * Lets the UI warn that recent months may be incomplete because
 * e-conomics period closure typically lags 1-3 weeks.
 */
public record CostDataFreshnessDTO(
        LocalDate latestExpenseDate,
        Integer daysSinceLatest,
        List<CompanyFreshness> perCompany
) {
    public record CompanyFreshness(
            String companyUuid,
            String companyName,
            LocalDate latestExpenseDate,
            Integer daysSinceLatest
    ) {}
}
