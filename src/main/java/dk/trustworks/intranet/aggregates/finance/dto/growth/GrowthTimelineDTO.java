package dk.trustworks.intranet.aggregates.finance.dto.growth;

import java.util.List;

/**
 * Full multi-year growth timeline for the executive dashboard's
 * Growth &amp; Scenarios tab.
 *
 * @param months           chronologically ordered months (from the first month of
 *                         {@code fact_company_revenue_mat}, 2017-07, through the
 *                         current — possibly partial — month)
 * @param costDataFromKey  first month key (YYYYMM) with GL-derived cost data;
 *                         months before this carry null cost fields
 * @param currentMonthKey  the current calendar month (YYYYMM) — its revenue and
 *                         cost figures are partial
 */
public record GrowthTimelineDTO(
        List<GrowthTimelineMonthDTO> months,
        String costDataFromKey,
        String currentMonthKey) {
}
