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
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledDeltaQuery;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledPaidGate;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
 * Per-invoice finalizer for the queued-internal-invoice batch. Each public {@code processOne*} /
 * discovery method runs in its OWN {@link Transactional.TxType#REQUIRES_NEW} transaction so that a
 * single invoice's e-conomics {@code book()} failure rolls back only that invoice — not the whole
 * batch run. Without this isolation a single failed booking marked the shared batch transaction
 * rollback-only, reverting every booking in the run to QUEUED while e-conomics kept them, causing
 * nightly re-booking duplicates.
 *
 * <p>The orchestration loop lives in {@link QueuedInternalInvoiceProcessorBatchlet}, which is
 * intentionally NON-transactional.
 *
 * SPEC-INV-001 §9.1, §9.2.
 */
@ApplicationScoped
@JBossLog
public class QueuedInternalInvoiceFinalizer {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** Result of attempting to finalize a single queued invoice. */
    public enum Outcome { PROCESSED, SKIPPED }

    @Inject
    InternalInvoiceOrchestrator internalOrchestrator;

    @Inject
    InvoiceAttributionService invoiceAttributionService;

    @Inject
    UserCompanyResolver userCompanyResolver;

    @Inject
    PricingEngine pricingEngine;

    @Inject
    SelfBilledDeltaQuery selfBilledDeltaQuery;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "feature.invoicing.internal.attribution-driven", defaultValue = "true")
    boolean attributionDrivenInternalInvoices;

    /**
     * First-pass discovery: uuids of all QUEUED INTERNAL invoices that reference another invoice.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<String> findFirstPassUuids() {
        List<Invoice> queuedInvoices = Invoice.list(
                "status = ?1 AND type = ?2 AND invoiceref > 0",
                InvoiceStatus.QUEUED, InvoiceType.INTERNAL
        );
        return queuedInvoices.stream().map(Invoice::getUuid).collect(Collectors.toList());
    }

    /**
     * Second-pass discovery (Feature 3c): uuids of QUEUED settlement INTERNALs that reference a
     * PHANTOM source.
     *
     * <p>Native, parameter-free discovery (selfbilled idiom): QUEUED settlement INTERNALs whose
     * invoice_ref_uuid points at a PHANTOM source. A separate native query keeps the PHANTOM-type
     * join out of HQL (no Panache active-record subquery precedent in this codebase).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<String> findSettlementUuids() {
        return em.createNativeQuery("""
                SELECT i.uuid
                FROM invoices i
                JOIN invoices src ON src.uuid = i.invoice_ref_uuid
                WHERE i.type = 'INTERNAL' AND i.status = 'QUEUED'
                  AND i.settlement_billing_client_uuid IS NOT NULL
                  AND i.settlement_year IS NOT NULL AND i.settlement_month IS NOT NULL
                  AND src.type = 'PHANTOM'
                """).getResultList();
    }

    /**
     * Finalize a single first-pass queued internal invoice in its own REQUIRES_NEW transaction.
     *
     * <p>Re-fetches the queued invoice by uuid (it may have changed since discovery), checks the
     * referenced invoice is PAID, optionally regenerates items from current attribution, then
     * delegates to {@link InternalInvoiceOrchestrator#finalizeAutomatically(String)} which creates
     * the e-conomics draft and books it. Any exception propagates and rolls back ONLY this invoice's
     * transaction (the orchestrator loop catches it and records a failure).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Outcome processOne(String queuedInvoiceUuid) {
        Invoice queuedInvoice = Invoice.<Invoice>find("uuid", queuedInvoiceUuid).firstResult();
        if (queuedInvoice == null) {
            log.warnf("Queued invoice %s no longer exists - skipping", queuedInvoiceUuid);
            return Outcome.SKIPPED;
        }

        // Find the referenced external invoice
        Invoice referencedInvoice = Invoice.find(
                "uuid = ?1",
                queuedInvoice.getInvoiceRefUuid()
        ).firstResult();

        if (referencedInvoice == null) {
            log.warnf("Queued invoice %s references non-existent invoice %s - skipping",
                    queuedInvoice.getUuid(), queuedInvoice.getInvoiceRefUuid());
            return Outcome.SKIPPED;
        }

        // Only proceed when the referenced invoice is confirmed PAID
        if (referencedInvoice.getEconomicsStatus() != EconomicsInvoiceStatus.PAID) {
            log.debugf("Queued invoice %s waiting for invoice %s to be PAID (current: %s)",
                    queuedInvoice.getUuid(),
                    referencedInvoice.getUuid(),
                    referencedInvoice.getEconomicsStatus());
            return Outcome.SKIPPED;
        }

        // Regenerate items from current source attribution BEFORE finalization
        // (spec §5.4). If the regeneration yields zero lines for this issuer
        // (attribution shifted so this issuer no longer has cross-company work),
        // delete the QUEUED invoice, log a WARN, and continue — no e-conomics
        // cleanup needed because QUEUED never created an e-conomics draft.
        if (attributionDrivenInternalInvoices
                && !regenerateQueuedItems(queuedInvoice, referencedInvoice)) {
            return Outcome.SKIPPED;
        }

        // Set dates before auto-finalization: invoicedate = today, duedate = tomorrow
        queuedInvoice.setInvoicedate(LocalDate.now());
        queuedInvoice.setDuedate(LocalDate.now().plusDays(1));

        log.infof("Auto-finalizing queued invoice %s (references paid invoice %s)",
                queuedInvoice.getUuid(), referencedInvoice.getUuid());

        // Auto-finalize: create draft + book immediately, no review step (SPEC-INV-001 §9.1)
        internalOrchestrator.finalizeAutomatically(queuedInvoice.getUuid());

        log.infof("Successfully auto-finalized queued invoice %s", queuedInvoice.getUuid());
        return Outcome.PROCESSED;
    }

    /**
     * Finalize a single QUEUED settlement INTERNAL (PHANTOM-referenced) in its own REQUIRES_NEW
     * transaction.
     *
     * <p>These never match the first pass — a settlement internal references a PHANTOM (not a real
     * source invoice with {@code economics_status = PAID}), and it carries DELTA lines that must NOT
     * be regenerated from source attribution (that would re-derive the full amount and over-book).
     * Instead they go through the self-billed paid-gate: a settlement internal may finalize only when
     * the client has paid every self-billing voucher backing its (client, consultant, work-period)
     * group — i.e. every backing voucher's 8610 'Samlekonto debitorer' remainder is exactly 0
     * (reusing {@link SelfBilledDeltaQuery#voucherRemainders} + {@link SelfBilledPaidGate#allPaid},
     * the SAME lookup as the workbench {@code /internals/queued} read — no duplicated SQL).
     * Fail-closed: a voucher with no 8610 row (null remainder) or an empty backing set means NOT paid
     * -> skip. No item regeneration.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Outcome processOneSettlement(String settlementInvoiceUuid) {
        Invoice internal = Invoice.<Invoice>find("uuid", settlementInvoiceUuid).firstResult();
        if (internal == null) {
            log.warnf("Settlement internal %s no longer exists - skipping", settlementInvoiceUuid);
            return Outcome.SKIPPED;
        }

        String consultant = settlementConsultant(internal);
        if (consultant == null) {
            log.warnf("Settlement internal %s has no single item consultant — skipping", internal.getUuid());
            return Outcome.SKIPPED;
        }
        List<SelfBilledPaidGate.VoucherRemainder> remainders = selfBilledDeltaQuery.voucherRemainders(
                internal.getSettlementBillingClientUuid(), internal.getSettlementDebtorCompanyuuid(),
                consultant, internal.getSettlementYear(), internal.getSettlementMonth());

        if (!SelfBilledPaidGate.allPaid(remainders)) {
            log.debugf("Settlement internal %s not yet paid (client=%s consultant=%s %d-%02d, "
                            + "backingVouchers=%d) — skipping",
                    internal.getUuid(), internal.getSettlementBillingClientUuid(), consultant,
                    internal.getSettlementYear(), internal.getSettlementMonth(), remainders.size());
            return Outcome.SKIPPED;
        }

        internal.setInvoicedate(LocalDate.now());
        internal.setDuedate(LocalDate.now().plusDays(1));
        log.infof("Auto-finalizing settlement internal %s (self-billing vouchers paid)", internal.getUuid());
        internalOrchestrator.finalizeAutomatically(internal.getUuid());
        log.infof("Successfully auto-finalized settlement internal %s", internal.getUuid());
        return Outcome.PROCESSED;
    }

    /** The single consultant on a settlement internal's items, or null if items carry zero or >1 consultants. */
    private String settlementConsultant(Invoice internal) {
        List<InvoiceItem> items = internal.getInvoiceitems();
        if (items == null || items.isEmpty()) return null;
        String consultant = null;
        for (InvoiceItem item : items) {
            if (item.consultantuuid == null || item.consultantuuid.isBlank()) continue;
            if (consultant == null) {
                consultant = item.consultantuuid;
            } else if (!consultant.equals(item.consultantuuid)) {
                return null;   // ambiguous — never guess
            }
        }
        return consultant;
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
            // Delete the QUEUED invoice and its items — no e-conomics cleanup needed. Delete via the
            // MANAGED entity so cascade REMOVE takes the eager-loaded items in the PC; a JPQL bulk item
            // delete bypasses the PC, leaving the cascade to issue per-row deletes that affect 0 rows ->
            // OptimisticLockException rolling back the whole tx (DB FK fk_invoiceitems_invoice ON DELETE
            // CASCADE, V173, is the backstop).
            queuedInvoice.delete();
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
