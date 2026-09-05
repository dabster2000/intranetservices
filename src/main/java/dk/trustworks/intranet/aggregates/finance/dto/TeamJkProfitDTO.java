package dk.trustworks.intranet.aggregates.finance.dto;

import dk.trustworks.intranet.aggregates.finance.dto.HourlyOverviewDTO.JkProfitTotals;

import java.util.List;

/**
 * JK Profit per junior (JK Team 2.0 WP6 §4.6.2 row 2.2.1) — Financial tab, hourly kind.
 * A junior with no paid hours is a zero-revenue row, never a fall-through.
 *
 * @param monthKey   the reporting month (last complete month), yyyyMM
 * @param fiscalYear the FY the to-date totals belong to
 */
public record TeamJkProfitDTO(
        String monthKey,
        int fiscalYear,
        JkProfitTotals month,
        JkProfitTotals fy,
        List<JuniorProfit> juniors
) {
    public record JuniorProfit(
            String userId,
            String firstname,
            String lastname,
            JkProfitTotals month,
            JkProfitTotals fy
    ) {}
}
