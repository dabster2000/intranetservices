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
) {}
