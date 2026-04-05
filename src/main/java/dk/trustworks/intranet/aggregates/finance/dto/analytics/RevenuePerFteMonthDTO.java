package dk.trustworks.intranet.aggregates.finance.dto.analytics;

/**
 * Monthly revenue per FTE data point for trailing 18 months.
 * Revenue per FTE = total net revenue / average headcount.
 */
public record RevenuePerFteMonthDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double totalRevenueDkk,
        double totalHeadcount,
        /** Revenue per FTE. Null if headcount is zero. */
        Double revenuePerFteDkk
) {}
