package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One month of cost-to-revenue data for the CXO Command Center.
 * Field names use Java camelCase; Jackson default serialization produces matching JSON keys.
 */
public record CostToRevenueDataPointDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double revenueDkk,
        double deliveryCostDkk,
        double opexDkk,
        Double costToRevenueRatioPct  // boxed: nullable when revenue == 0
) {
    public CostToRevenueDataPointDTO {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
    }
}
