package dk.trustworks.intranet.aggregates.finance.dto.analytics;

import java.util.List;

/**
 * Intercompany settlement table data for a fiscal year.
 * Each row represents a payer-receiver pair with aggregated settlement figures.
 */
public record IntercompanyTableDTO(
        /** Settlement rows ordered by gross amount descending. */
        List<IntercompanyTableRowDTO> rows,
        /** Fiscal year for the data. */
        int fiscalYear
) {
    /** A single payer-receiver settlement row. */
    public record IntercompanyTableRowDTO(
            String payerCompanyUuid,
            String payerCompanyName,
            String receiverCompanyUuid,
            String receiverCompanyName,
            double grossAmountDkk,
            double netSettlementDkk,
            String settlementStatus,
            int invoiceCount
    ) {}
}
