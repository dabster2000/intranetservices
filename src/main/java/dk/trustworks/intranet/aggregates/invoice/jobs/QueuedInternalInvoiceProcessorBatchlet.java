package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.pricing.PriceResult;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceLineGenerator;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceOrchestrator;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceAttributionService;
import dk.trustworks.intranet.aggregates.invoice.services.SourceItemMerger;
import dk.trustworks.intranet.aggregates.invoice.services.UserCompanyResolver;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p>Individual failures are logged as warnings and do not stop processing of remaining invoices.
 *
 * SPEC-INV-001 §9.1, §9.2.
 */
@JBossLog
@Named("queuedInternalInvoiceProcessorBatchlet")
@Dependent
@BatchExceptionTracking
public class QueuedInternalInvoiceProcessorBatchlet extends AbstractBatchlet {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Inject
    InternalInvoiceOrchestrator internalOrchestrator;

    @Inject
    InvoiceAttributionService invoiceAttributionService;

    @Inject
    UserCompanyResolver userCompanyResolver;

    @Inject
    PricingEngine pricingEngine;

    @ConfigProperty(name = "feature.invoicing.internal.attribution-driven", defaultValue = "true")
    boolean attributionDrivenInternalInvoices;

    @Override
    @Transactional
    public String process() throws Exception {
        log.info("QueuedInternalInvoiceProcessorBatchlet started");

        // Find all QUEUED INTERNAL invoices that reference another invoice
        List<Invoice> queuedInvoices = Invoice.list(
                "status = ?1 AND type = ?2 AND invoiceref > 0",
                InvoiceStatus.QUEUED, InvoiceType.INTERNAL
        );

        log.infof("Found %d queued internal invoices to process", queuedInvoices.size());

        int processed = 0;
        int skipped = 0;
        int failed = 0;

        for (Invoice queuedInvoice : queuedInvoices) {
            try {
                // Find the referenced external invoice
                Invoice referencedInvoice = Invoice.find(
                        "uuid = ?1",
                        queuedInvoice.getInvoiceRefUuid()
                ).firstResult();

                if (referencedInvoice == null) {
                    log.warnf("Queued invoice %s references non-existent invoice %s - skipping",
                            queuedInvoice.getUuid(), queuedInvoice.getInvoiceRefUuid());
                    skipped++;
                    continue;
                }

                // Only proceed when the referenced invoice is confirmed PAID
                if (referencedInvoice.getEconomicsStatus() != EconomicsInvoiceStatus.PAID) {
                    log.debugf("Queued invoice %s waiting for invoice %s to be PAID (current: %s)",
                            queuedInvoice.getUuid(),
                            referencedInvoice.getUuid(),
                            referencedInvoice.getEconomicsStatus());
                    skipped++;
                    continue;
                }

                // Regenerate items from current source attribution BEFORE finalization
                // (spec §5.4). If the regeneration yields zero lines for this issuer
                // (attribution shifted so this issuer no longer has cross-company work),
                // delete the QUEUED invoice, log a WARN, and continue — no e-conomics
                // cleanup needed because QUEUED never created an e-conomics draft.
                if (attributionDrivenInternalInvoices
                        && !regenerateQueuedItems(queuedInvoice, referencedInvoice)) {
                    skipped++;
                    continue;
                }

                // Set dates before auto-finalization: invoicedate = today, duedate = tomorrow
                queuedInvoice.setInvoicedate(LocalDate.now());
                queuedInvoice.setDuedate(LocalDate.now().plusDays(1));

                log.infof("Auto-finalizing queued invoice %s (references paid invoice %s)",
                        queuedInvoice.getUuid(), referencedInvoice.getUuid());

                // Auto-finalize: create draft + book immediately, no review step (SPEC-INV-001 §9.1)
                internalOrchestrator.finalizeAutomatically(queuedInvoice.getUuid());

                log.infof("Successfully auto-finalized queued invoice %s", queuedInvoice.getUuid());
                processed++;

            } catch (Exception e) {
                log.warnf(e, "Auto-finalize failed for queued internal invoice %s", queuedInvoice.getUuid());
                failed++;
                // Continue processing remaining invoices
            }
        }

        log.infof("QueuedInternalInvoiceProcessorBatchlet completed: total=%d, processed=%d, skipped=%d, failed=%d",
                queuedInvoices.size(), processed, skipped, failed);

        return "COMPLETED";
    }

    /**
     * Regenerate the items on a QUEUED internal invoice from the source invoice's
     * current attribution state, filtered to this invoice's issuer company.
     *
     * <p>Returns {@code true} if regeneration produced one or more lines; the caller
     * proceeds with finalization. Returns {@code false} if no cross-company lines
     * remain — in that case the QUEUED invoice is deleted and a WARN is logged
     * (per user decision #2 from spec review: log only, no email/Slack).
     *
     * <p>The WARN message contains both the issuer {@code companyUuid} and the
     * source invoice's period formatted as {@code YYYY-MM} so Ops can quickly
     * correlate the event with a period in the controlling view.
     */
    private boolean regenerateQueuedItems(Invoice queuedInvoice, Invoice sourceInvoice) {
        List<InvoiceItemAttribution> attributions =
                invoiceAttributionService.getInvoiceAttributions(sourceInvoice.getUuid());
        Set<String> consultantUuids = attributions.stream()
                .map(a -> a.consultantUuid)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());
        LocalDate asOf = sourceInvoice.getInvoicedate() != null
                ? sourceInvoice.getInvoicedate()
                : LocalDate.now();
        Map<String, String> userCompanies = userCompanyResolver.resolveCompanies(consultantUuids, asOf);
        String sourceCompanyUuid = sourceInvoice.getCompany() != null
                ? sourceInvoice.getCompany().getUuid() : null;
        String issuerUuid = queuedInvoice.getCompany() != null
                ? queuedInvoice.getCompany().getUuid() : null;

        // Merge persisted source items with synthetic CALCULATED items from the pricing
        // engine (spec §6.4) before regeneration. Required for sources whose CALCULATED
        // discount/fee lines were never persisted — without the merge the nightly
        // batchlet would over-bill the issuer.
        long mergeStartNanos = System.nanoTime();
        List<InvoiceItem> persisted = sourceInvoice.getInvoiceitems() != null
                ? sourceInvoice.getInvoiceitems() : List.of();
        List<InvoiceItem> synthetics;
        try {
            Map<String, String> cti = loadContractTypeItems(sourceInvoice.getContractuuid());
            PriceResult pr = pricingEngine.price(sourceInvoice, cti);
            synthetics = pr.syntheticItems != null ? pr.syntheticItems : List.of();
        } catch (Exception e) {
            log.warnf(e, "Pricing engine failed for source invoice %s — falling back to "
                    + "persisted items only", sourceInvoice.getUuid());
            synthetics = List.of();
        }
        List<InvoiceItem> mergedItems = SourceItemMerger.merge(persisted, synthetics);
        long durationMs = (System.nanoTime() - mergeStartNanos) / 1_000_000L;

        // Per spec O7.8 — structured per-source instrumentation so we can detect a
        // batchlet runtime regression and decide whether the pricing-cache phase
        // becomes necessary.
        log.infof("InternalInvoiceMerge: sourceUuid=%s persistedCount=%d syntheticCount=%d "
                        + "mergedCount=%d durationMs=%d",
                sourceInvoice.getUuid(), persisted.size(), synthetics.size(),
                mergedItems.size(), durationMs);

        // Synthesize in-memory attributions for synthetic CALCULATED items so the
        // generator emits internal lines for them (spec §6.4 option (a)). Without
        // this step every synthetic CALCULATED line would be silently dropped by
        // {@link InternalInvoiceLineGenerator}.
        Set<String> persistedItemUuids = new HashSet<>(persisted.size() * 2);
        Set<String> baseItemUuids = new HashSet<>();
        for (InvoiceItem p : persisted) {
            if (p == null || p.uuid == null) continue;
            persistedItemUuids.add(p.uuid);
            if (p.origin == dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin.BASE) {
                baseItemUuids.add(p.uuid);
            }
        }
        List<InvoiceItem> syntheticCalculated = new ArrayList<>();
        for (InvoiceItem m : mergedItems) {
            if (m == null || m.uuid == null) continue;
            if (m.origin != dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin.CALCULATED) continue;
            if (persistedItemUuids.contains(m.uuid)) continue;
            syntheticCalculated.add(m);
        }
        List<InvoiceItemAttribution> effectiveAttributions = attributions;
        if (!syntheticCalculated.isEmpty()) {
            List<InvoiceItemAttribution> syntheticAttrs = SourceItemMerger.synthesizeAttributionsFor(
                    syntheticCalculated, attributions, baseItemUuids);
            if (!syntheticAttrs.isEmpty()) {
                effectiveAttributions = new ArrayList<>(attributions.size() + syntheticAttrs.size());
                effectiveAttributions.addAll(attributions);
                effectiveAttributions.addAll(syntheticAttrs);
            }
        }

        Map<String, List<InvoiceItem>> grouped = InternalInvoiceLineGenerator.generate(
                sourceCompanyUuid, mergedItems, effectiveAttributions, userCompanies);

        List<InvoiceItem> newLines = grouped.getOrDefault(issuerUuid, List.of());
        long previousCount = queuedInvoice.getInvoiceitems() != null
                ? queuedInvoice.getInvoiceitems().size() : 0;

        if (newLines.isEmpty()) {
            String period = asOf.format(PERIOD_FORMAT);
            log.warnf("Queued internal invoice %s for companyUuid=%s period=%s has no "
                            + "cross-company attribution remaining — deleting QUEUED row (no e-conomics "
                            + "draft existed).",
                    queuedInvoice.getUuid(), issuerUuid, period);
            // Delete the QUEUED invoice and its items — no e-conomics cleanup needed.
            InvoiceItem.delete("invoiceuuid", queuedInvoice.getUuid());
            Invoice.deleteById(queuedInvoice.getUuid());
            return false;
        }

        // Replace items on the QUEUED invoice and log the delta.
        InvoiceItem.delete("invoiceuuid", queuedInvoice.getUuid());
        if (queuedInvoice.getInvoiceitems() != null) {
            queuedInvoice.getInvoiceitems().clear();
        }
        int position = 1;
        for (InvoiceItem line : newLines) {
            line.invoiceuuid = queuedInvoice.getUuid();
            line.position = position++;
            InvoiceItem.persist(line);
            if (queuedInvoice.getInvoiceitems() != null) {
                queuedInvoice.getInvoiceitems().add(line);
            }
        }
        log.infof("Regenerated QUEUED invoice %s items: %d -> %d",
                queuedInvoice.getUuid(), previousCount, newLines.size());
        return true;
    }

    /**
     * Load {@code contract_type_items} key/value pairs for the source contract — the
     * pricing engine consumes these for dynamic discount-parameter resolution.
     */
    private Map<String, String> loadContractTypeItems(String contractuuid) {
        Map<String, String> cti = new HashMap<>();
        if (contractuuid == null || contractuuid.isBlank()) return cti;
        ContractTypeItem.<ContractTypeItem>find("contractuuid", contractuuid)
                .list().forEach(ct -> cti.put(ct.getKey(), ct.getValue()));
        return cti;
    }
}
