package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.jobs.QueuedInternalInvoiceFinalizer.Outcome;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceOrchestrator;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledDeltaQuery;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledPaidGate;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * Processes queued INTERNAL invoices, creating them automatically (no review step) when
 * their referenced external invoice has been PAID in e-conomics.
 *
 * <p>This batch job runs nightly and:
 * <ol>
 *   <li>Finds all INTERNAL invoices with status QUEUED</li>
 *   <li>Checks if their referenced invoice has economics_status = PAID</li>
 *   <li>For each eligible invoice: delegates to
 *       {@link InternalInvoiceOrchestrator#finalizeAutomatically(String)} which creates the
 *       e-conomics draft and immediately books it in a single transaction (SPEC-INV-001 §9.1).</li>
 * </ol>
 *
 * <p>This batchlet is intentionally NON-transactional: it is a pure orchestrator loop. Each
 * invoice is finalized by {@link QueuedInternalInvoiceFinalizer} in its OWN
 * {@code REQUIRES_NEW} transaction so that one invoice's e-conomics {@code book()} failure rolls
 * back only that invoice — every successful booking in the run still commits. (Previously the
 * loop held one shared transaction; a single failed booking marked it rollback-only, reverting
 * every booking to QUEUED while e-conomics kept them, which caused nightly re-booking duplicates.)
 *
 * <p>Individual failures are logged as warnings and do not stop processing of remaining invoices.
 *
 * SPEC-INV-001 §9.1, §9.2.
 */
@JBossLog
@Named("queuedInternalInvoiceProcessorBatchlet")
@Dependent
@BatchExceptionTracking
public class QueuedInternalInvoiceProcessorBatchlet extends AbstractBatchlet {

    @Inject
    QueuedInternalInvoiceFinalizer finalizer;

    @Override
    public String process() throws Exception {
        log.info("QueuedInternalInvoiceProcessorBatchlet started");

        // First pass: QUEUED INTERNAL invoices that reference another (real, PAID) invoice.
        List<String> firstPass = finalizer.findFirstPassUuids();
        log.infof("Found %d queued internal invoices to process", firstPass.size());

        int processed = 0;
        int skipped = 0;
        int failed = 0;

        for (String uuid : firstPass) {
            try {
                Outcome outcome = finalizer.processOne(uuid);
                if (outcome == Outcome.PROCESSED) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warnf(e, "Auto-finalize failed for queued internal invoice %s", uuid);
                failed++;
                // Continue processing remaining invoices — each ran in its own REQUIRES_NEW tx.
            }
        }

        log.infof("QueuedInternalInvoiceProcessorBatchlet completed: total=%d, processed=%d, skipped=%d, failed=%d",
                firstPass.size(), processed, skipped, failed);

        processSettlementInternals();

        return "COMPLETED";
    }

    /**
     * Second pass (Feature 3c): auto-finalize QUEUED settlement INTERNALs that reference a PHANTOM
     * source.
     *
     * <p>These never match the first pass — a settlement internal references a PHANTOM (not a real
     * source invoice with {@code economics_status = PAID}), and it carries DELTA lines that must NOT
     * be regenerated from source attribution (that would re-derive the full amount and over-book).
     * Instead they go through the self-billed paid-gate: a settlement internal may finalize only when
     * the client has paid every self-billing voucher backing its (client, consultant, work-period)
     * group — i.e. every backing voucher's 8610 'Samlekonto debitorer' remainder is exactly 0
     * (reusing {@link SelfBilledDeltaQuery#voucherRemainders} + {@link SelfBilledPaidGate#allPaid},
     * the SAME lookup as the workbench {@code /internals/queued} read — no duplicated SQL). Fail-closed:
     * a voucher with no 8610 row (null remainder) or an empty backing set means NOT paid -> skip.
     * No item regeneration.
     */
    private void processSettlementInternals() {
        List<String> settlement = finalizer.findSettlementUuids();
        log.infof("Found %d queued settlement internals (PHANTOM-referenced) to evaluate", settlement.size());

        int processed = 0, skipped = 0, failed = 0;
        for (String uuid : settlement) {
            try {
                Outcome outcome = finalizer.processOneSettlement(uuid);
                if (outcome == Outcome.PROCESSED) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warnf(e, "Auto-finalize failed for settlement internal %s", uuid);
                failed++;
            }
        }

        log.infof("QueuedInternalInvoiceProcessorBatchlet settlement pass completed: total=%d, processed=%d, "
                        + "skipped=%d, failed=%d",
                settlement.size(), processed, skipped, failed);
    }
}
