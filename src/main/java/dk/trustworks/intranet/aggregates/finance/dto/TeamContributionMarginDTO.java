package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Team contribution margin waterfall for a fiscal year.
 */
public record TeamContributionMarginDTO(
        String teamId,
        String teamName,
        int fiscalYear,
        /** Total team revenue (DKK) */
        double revenue,
        /** Total team salary cost (DKK) */
        double salaryCost,
        /** Allocated share of company OPEX (non-salary overhead) (DKK) */
        double allocatedOpex,
        /** Revenue - salaryCost */
        double grossMargin,
        /** Revenue - salaryCost - allocatedOpex */
        double contributionMargin,
        /** Gross margin as percentage of revenue */
        Double grossMarginPercent,
        /** Contribution margin as percentage of revenue */
        Double contributionMarginPercent
) {}
