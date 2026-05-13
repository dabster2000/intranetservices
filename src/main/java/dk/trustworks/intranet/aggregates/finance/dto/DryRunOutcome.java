package dk.trustworks.intranet.aggregates.finance.dto;

import dk.trustworks.intranet.aggregates.finance.services.EconomicRevenueImportService;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Structured result of one {@code EconomicRevenueImportService.refresh()} run.
 *
 * <p>Returned regardless of {@code economics.import.dry-run} mode so the
 * {@link dk.trustworks.intranet.aggregates.finance.jobs.EconomicRevenueImportBatchlet
 * EconomicRevenueImportBatchlet} can emit the same structured log line in both
 * modes — the only difference between dry-run and live is whether
 * {@link #totalActualInserts} is zero (dry-run) or matches
 * {@link #totalIntendedInserts} (live, minus any race-loss to V338's unique
 * index which raises {@link #totalActualInserts} by zero but bumps
 * {@link EconomicRevenueImportService.DedupLayer#LAYER_3_ENTRY_COLLISION}).
 *
 * @param totalIntendedInserts number of aggregated vouchers that survived all
 *                             4 dedup layers and would be inserted in live mode
 * @param totalActualInserts   number of aggregated vouchers that successfully
 *                             produced an invoices + invoiceitems row pair.
 *                             Equals 0 in dry-run mode. In live mode equals
 *                             {@code totalIntendedInserts} minus race-condition
 *                             losses caught by V338's
 *                             {@code uniq_invoices_economic_entry} index.
 * @param perCompanyDkk        company UUID → SUM(ABS(amount)) of inserted/intended
 *                             vouchers, in DKK base currency. Used for the
 *                             A/S=18.5M / TECH=0 / CYBER=0 dry-run assertion
 *                             gate before PR 3 cutover.
 * @param perAccountDkk        e-conomic account number → SUM(ABS(amount)) of
 *                             inserted/intended vouchers in DKK. Surfaces
 *                             account-level distribution for sanity-checking
 *                             account filtering rules.
 * @param skippedByLayer       which dedup layer skipped each rejected voucher.
 *                             Layers fail-fast: layer N is queried only when
 *                             layers 1..N-1 missed.
 * @param dryRun               echoes the resolved
 *                             {@code economics.import.dry-run} property at
 *                             refresh-call time, so log consumers can confirm
 *                             which mode produced this outcome.
 */
public record DryRunOutcome(
        int totalIntendedInserts,
        int totalActualInserts,
        Map<String, BigDecimal> perCompanyDkk,
        Map<Integer, BigDecimal> perAccountDkk,
        Map<EconomicRevenueImportService.DedupLayer, Integer> skippedByLayer,
        boolean dryRun
) {
}
