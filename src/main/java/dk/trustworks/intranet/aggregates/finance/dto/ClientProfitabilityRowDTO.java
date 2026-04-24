package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * One row in the Client Profitability Diagnostic overview (TTM).
 * All amounts in DKK. Driver fields (rateGapDkk, unusedContractDkk) are ≥ 0.
 * Invariant: actualProfitDkk + rateGapDkk + unusedContractDkk = targetProfitDkk (within rounding).
 */
public record ClientProfitabilityRowDTO(
        String clientId,
        String clientName,
        String sector,
        double actualRevenueDkk,
        double actualSalaryDkk,
        double actualExternalDkk,
        double actualExpensesDkk,
        double allocatedOpexDkk,
        double actualProfitDkk,
        double rateGapDkk,
        double unusedContractDkk,
        double targetProfitDkk,
        int consultantCount
) {}
