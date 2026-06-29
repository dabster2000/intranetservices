package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import java.util.List;

/**
 * Reconciliation report for the one-time historical backfill that stamps the APPROVED
 * InvoiceBonus rows which already funded paid fiscal years, so they are not re-funded once
 * the per-invoice "consumed" guard is live. Run with {@code dryRun=true} first.
 */
public record PartnerBonusBackfillReport(
        boolean dryRun,
        int groupsConsidered,
        int groupsApplied,
        int invoicesStamped,
        List<Entry> entries
) {
    public record Entry(
            int fiscalYear,
            String groupUuid,
            String groupName,
            int partnerCount,
            boolean paid,
            boolean skipped,
            String skipReason,
            int invoiceCount,
            int bonusRowsStamped,
            double salesBasis,
            double salesBonusPerPartner,
            boolean applied
    ) {}
}
