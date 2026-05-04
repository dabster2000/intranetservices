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
        Double grossMarginPct  // boxed: nullable per frontend contract; HAVING clause currently filters zero-revenue months so production responses always have a value, but defensive null-safety is preserved
) {
    public GrossMarginTrendDataPointDTO {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
    }
}
