package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Freshness of the materialised fact tables feeding the CXO dashboards.
 * All timestamps are ISO-8601 strings in the database server's timezone,
 * lifted from {@code INFORMATION_SCHEMA.TABLES.UPDATE_TIME} so the UI can
 * display "Data as of …" without guessing.
 */
public record FactFreshnessDTO(
        String factClientRevenueMat,
        String factProjectFinancialsMat,
        String factOpexMat,
        String mostRecent
) {}
