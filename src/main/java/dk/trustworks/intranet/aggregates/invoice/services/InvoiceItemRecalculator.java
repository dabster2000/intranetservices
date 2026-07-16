package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.PracticeInvoiceItemDeliverySource;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin.BASE;
import static dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType.CREDIT_NOTE;

/**
 * Canonical home for the invoice line-item recalculation pipeline.
 *
 * Rebuilds the invoice items: deletes CALCULATED items, re-runs the pricing
 * engine, and repopulates the collection on the entity. Used by both the draft
 * update path and the e-conomic finalization orchestrator.
 */
@ApplicationScoped
public class InvoiceItemRecalculator {

    @Inject
    EntityManager em;

    @Inject
    PricingEngine pricingEngine;

    @Transactional
    public void recalculateInvoiceItems(Invoice invoice) {
        recalculateInvoiceItems(invoice, null);
    }

    /**
     * Recalculates invoice items while retaining the persisted source month captured before a
     * draft header update. The caller is {@link InvoiceService#updateDraftInvoice(Invoice)}, whose
     * transaction keeps both old/new watermark mutations atomic with the item replacement.
     */
    void recalculateInvoiceItems(Invoice invoice, LocalDate previousRecognizedMonth) {
        List<PracticeInvoiceItemDeliverySource> managedDeliveryLineage =
                loadDeliveryLineageByInvoiceUuid(invoice.getUuid());
        List<DeliveryLineageSnapshot> deliveryLineage = managedDeliveryLineage.stream()
                .map(DeliveryLineageSnapshot::from)
                .toList();

        var baseItemData = invoice.getInvoiceitems().stream()
                .filter(ii -> !ii.isEffectivelyCalculated())
                // Clone each item as a new entity to avoid Hibernate managed-entity issues.
                // A JPQL bulk delete only removes rows from the DB, not from the persistence
                // context, so calling persist() on the original (still-managed) instances is
                // silently ignored. Creating fresh instances guarantees an INSERT.
                .map(ii -> cloneBaseItem(invoice, ii))
                .toList();

        List<InvoiceItem> oldManagedItems = new ArrayList<>(invoice.getInvoiceitems());

        // Delete from DB first (auto-flush sees unchanged collection → no interference)
        deleteItemsByInvoiceUuid(invoice.getUuid());
        em.flush();

        // CRITICAL: Clear the PersistentBag to break the CascadeType.ALL cascade path.
        // Without this, the bag re-attaches detached items during subsequent flushes,
        // causing "different object with same identifier" on persist.
        invoice.invoiceitems.clear();
        oldManagedItems.forEach(em::detach);
        // The invoice-item bulk delete cascades to contribution-only delivery lineage in
        // the database, but Hibernate does not evict those managed child entities. Detach
        // them before recreating surviving rows with their original composite identifiers.
        managedDeliveryLineage.forEach(em::detach);

        persistItems(baseItemData);

        // Recalculation is implemented as delete-and-reinsert even when a BASE item is
        // unchanged. Preserve the server-owned proof byte-for-byte only for the same
        // surviving item UUID. In particular, never mint a replacement fingerprint or
        // move proof to a newly-created item: if the item values changed, the unchanged
        // stored fingerprint deliberately becomes stale and the materializer fails closed.
        Set<String> survivingBaseItemUuids = new HashSet<>();
        baseItemData.forEach(item -> survivingBaseItemUuids.add(item.getUuid()));
        List<PracticeInvoiceItemDeliverySource> survivingDeliveryLineage = deliveryLineage.stream()
                .filter(row -> survivingBaseItemUuids.contains(row.invoiceItemUuid()))
                .map(DeliveryLineageSnapshot::toEntity)
                .toList();
        if (!survivingDeliveryLineage.isEmpty()) {
            persistDeliveryLineage(survivingDeliveryLineage);
        }

        // PricingEngine.price() reads invoice.invoiceitems to compute sumBefore.
        // Repopulate with the freshly persisted BASE items BEFORE pricing — otherwise
        // sumBefore is 0 and percent-based discounts (e.g. NOVO_MSP_2025 1.8% MSP fee)
        // collapse to delta=0 and no synthetic line is emitted.
        invoice.invoiceitems.addAll(baseItemData);

        Map<String, String> cti = loadContractTypeItems(invoice.getContractuuid());

        var pr = pricingEngine.price(invoice, cti);

        pr.syntheticItems.forEach(ii -> ii.setInvoiceuuid(invoice.getUuid()));
        persistItems(pr.syntheticItems);

        invoice.invoiceitems.addAll(pr.syntheticItems);
        invoice.sumBeforeDiscounts = pr.sumBeforeDiscounts.doubleValue();
        invoice.sumAfterDiscounts  = pr.sumAfterDiscounts.doubleValue();
        invoice.vatAmount          = pr.vatAmount.doubleValue();
        invoice.grandTotal         = pr.grandTotal.doubleValue();
        invoice.calculationBreakdown = pr.breakdown;

        // Mark even a byte-identical restore: the physical delete/insert and its source
        // version must commit or roll back together. Removed rows are marked as an
        // invalidation and are never silently reassigned to replacement invoice items.
        if (!deliveryLineage.isEmpty()) {
            affectedRecognizedMonths(invoice, previousRecognizedMonth)
                    .forEach(month -> markDeliveryLineageChanged(invoice, month));
        }
    }

    static List<LocalDate> affectedRecognizedMonths(
            Invoice invoice, LocalDate previousRecognizedMonth) {
        LocalDate currentRecognizedMonth = recognizedMonth(invoice);
        LinkedHashSet<LocalDate> months = new LinkedHashSet<>();
        if (previousRecognizedMonth != null) {
            months.add(previousRecognizedMonth.withDayOfMonth(1));
        }
        if (currentRecognizedMonth != null) {
            months.add(currentRecognizedMonth);
        } else {
            // Preserve the existing global/unknown marker when the new owning month is invalid.
            months.add(null);
        }
        return new ArrayList<>(months);
    }

    static LocalDate recognizedMonth(Invoice invoice) {
        if (invoice == null) return null;
        LocalDate date = invoice.getInvoicedate();
        if (date == null && invoice.getYear() > 0 && invoice.getMonth() > 0) {
            date = LocalDate.of(invoice.getYear(), invoice.getMonth(), 1);
        }
        return date == null ? null : date.withDayOfMonth(1);
    }

    /**
     * Clones a BASE line while retaining only server-written credit-copy evidence.  Pricing
     * provenance belongs to calculated output and is intentionally not copied onto arbitrary
     * base rows.  A client-created or unproven credit row therefore remains unlinked.
     */
    static InvoiceItem cloneBaseItem(Invoice invoice, InvoiceItem source) {
        InvoiceItem clone = new InvoiceItem(
                source.getUuid(),
                source.getConsultantuuid(),
                source.getItemname(),
                source.getDescription(),
                source.getRate(),
                source.getHours(),
                source.getPosition(),
                invoice.getUuid(),
                BASE);
        if (invoice.getType() == CREDIT_NOTE
                && source.getSourceItemUuid() != null
                && !source.getSourceItemUuid().isBlank()
                && source.getCreditCopyKind() != null
                && !"NONE".equals(source.getCreditCopyKind())
                && source.getCreditCopyFingerprint() != null) {
            clone.setSourceItemUuid(source.getSourceItemUuid());
            clone.setSourceAttributionUuid(source.getSourceAttributionUuid());
            clone.setCreditCopyKind(source.getCreditCopyKind());
            clone.setCreditCopyScope(source.getCreditCopyScope());
            clone.setCreditCopyScale(source.getCreditCopyScale());
            clone.setCreditCopyOriginalSourceNativeAmount(source.getCreditCopyOriginalSourceNativeAmount());
            clone.setCreditCopyFingerprint(source.getCreditCopyFingerprint());
        }
        return clone;
    }

    /** Hook for tests: bulk-deletes invoice items via Panache. */
    protected void deleteItemsByInvoiceUuid(String invoiceUuid) {
        InvoiceItem.delete("invoiceuuid LIKE ?1", invoiceUuid);
    }

    /** Hook for tests: persists invoice items via Panache. */
    protected void persistItems(Iterable<InvoiceItem> items) {
        InvoiceItem.persist(items);
    }

    /** Hook for tests: loads all contribution-only delivery lineage owned by an invoice. */
    protected List<PracticeInvoiceItemDeliverySource> loadDeliveryLineageByInvoiceUuid(
            String invoiceUuid) {
        return em.createQuery("""
                        SELECT lineage
                        FROM PracticeInvoiceItemDeliverySource lineage
                        WHERE lineage.invoiceItemUuid IN (
                            SELECT item.uuid
                            FROM InvoiceItem item
                            WHERE item.invoiceuuid = :invoiceUuid
                        )
                        ORDER BY lineage.invoiceItemUuid, lineage.workUuid
                        """, PracticeInvoiceItemDeliverySource.class)
                .setParameter("invoiceUuid", invoiceUuid)
                .getResultList();
    }

    /** Hook for tests: restores server-owned contribution lineage after item reinsertion. */
    protected void persistDeliveryLineage(Iterable<PracticeInvoiceItemDeliverySource> rows) {
        PracticeInvoiceItemDeliverySource.persist(rows);
    }

    /** Advances the contribution attribution source version in the recalculation transaction. */
    protected void markDeliveryLineageChanged(Invoice invoice, LocalDate sourceMonth) {
        em.createNativeQuery("CALL sp_mark_practice_revenue_source_changed('INVOICE_ATTRIBUTION', :month)")
                .setParameter("month", sourceMonth)
                .executeUpdate();
    }

    /** Hook for tests: loads {key → value} entries from {@code contract_type_items}. */
    protected Map<String, String> loadContractTypeItems(String contractuuid) {
        Map<String, String> cti = new HashMap<>();
        ContractTypeItem.<ContractTypeItem>find("contractuuid", contractuuid)
                .list().forEach(ct -> cti.put(ct.getKey(), ct.getValue()));
        return cti;
    }

    /** Immutable copy used across the invoice-item delete/cascade boundary. */
    private record DeliveryLineageSnapshot(
            String invoiceItemUuid,
            String workUuid,
            String registrantUuid,
            String effectiveConsultantUuid,
            LocalDate deliveryDate,
            String taskUuid,
            String projectUuid,
            String contractUuid,
            String contractProjectUuid,
            String contractConsultantUuid,
            BigDecimal normalizedDuration,
            BigDecimal normalizedRate,
            BigDecimal deliveryValue,
            String rateResolutionStatus,
            String contributionAlgorithmVersion,
            String itemFingerprint,
            String distributionFingerprint,
            LocalDateTime createdAt) {

        private static DeliveryLineageSnapshot from(PracticeInvoiceItemDeliverySource row) {
            return new DeliveryLineageSnapshot(row.invoiceItemUuid, row.workUuid, row.registrantUuid,
                    row.effectiveConsultantUuid, row.deliveryDate, row.taskUuid, row.projectUuid,
                    row.contractUuid, row.contractProjectUuid, row.contractConsultantUuid,
                    row.normalizedDuration, row.normalizedRate, row.deliveryValue,
                    row.rateResolutionStatus, row.contributionAlgorithmVersion, row.itemFingerprint,
                    row.distributionFingerprint, row.createdAt);
        }

        private PracticeInvoiceItemDeliverySource toEntity() {
            PracticeInvoiceItemDeliverySource row = new PracticeInvoiceItemDeliverySource();
            row.invoiceItemUuid = invoiceItemUuid;
            row.workUuid = workUuid;
            row.registrantUuid = registrantUuid;
            row.effectiveConsultantUuid = effectiveConsultantUuid;
            row.deliveryDate = deliveryDate;
            row.taskUuid = taskUuid;
            row.projectUuid = projectUuid;
            row.contractUuid = contractUuid;
            row.contractProjectUuid = contractProjectUuid;
            row.contractConsultantUuid = contractConsultantUuid;
            row.normalizedDuration = normalizedDuration;
            row.normalizedRate = normalizedRate;
            row.deliveryValue = deliveryValue;
            row.rateResolutionStatus = rateResolutionStatus;
            row.contributionAlgorithmVersion = contributionAlgorithmVersion;
            row.itemFingerprint = itemFingerprint;
            row.distributionFingerprint = distributionFingerprint;
            row.createdAt = createdAt;
            return row;
        }
    }
}
