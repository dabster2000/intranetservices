package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One month of gross margin data for the CXO Command Center.
 * Only months with positive revenue are returned (filter applied SQL-side via HAVING).
 */
public record GrossMarginTrendDataPointDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double totalRevenueDkk,
        double totalCostDkk,
        Double grossMarginPct  // boxed: nullable per frontend contract (`number | null`)
) {}
