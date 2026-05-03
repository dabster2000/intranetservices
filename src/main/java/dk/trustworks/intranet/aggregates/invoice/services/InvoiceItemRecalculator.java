package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin.BASE;

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
        var baseItemData = invoice.getInvoiceitems().stream()
                .filter(ii -> !ii.isEffectivelyCalculated())
                // Clone each item as a new entity to avoid Hibernate managed-entity issues.
                // A JPQL bulk delete only removes rows from the DB, not from the persistence
                // context, so calling persist() on the original (still-managed) instances is
                // silently ignored. Creating fresh instances guarantees an INSERT.
                .map(ii -> new InvoiceItem(
                        ii.getUuid(),           // preserve existing UUID
                        ii.getConsultantuuid(),
                        ii.getItemname(),
                        ii.getDescription(),
                        ii.getRate(),
                        ii.getHours(),
                        ii.getPosition(),
                        invoice.getUuid(),
                        BASE))
                .toList();

        List<InvoiceItem> oldManagedItems = new ArrayList<>(invoice.getInvoiceitems());

        // Delete from DB first (auto-flush sees unchanged collection → no interference)
        InvoiceItem.delete("invoiceuuid LIKE ?1", invoice.getUuid());
        em.flush();

        // CRITICAL: Clear the PersistentBag to break the CascadeType.ALL cascade path.
        // Without this, the bag re-attaches detached items during subsequent flushes,
        // causing "different object with same identifier" on persist.
        invoice.invoiceitems.clear();
        oldManagedItems.forEach(em::detach);

        InvoiceItem.persist(baseItemData);

        // PricingEngine.price() reads invoice.invoiceitems to compute sumBefore.
        // Repopulate with the freshly persisted BASE items BEFORE pricing — otherwise
        // sumBefore is 0 and percent-based discounts (e.g. NOVO_MSP_2025 1.8% MSP fee)
        // collapse to delta=0 and no synthetic line is emitted.
        invoice.invoiceitems.addAll(baseItemData);

        Map<String, String> cti = new HashMap<>();
        ContractTypeItem.<ContractTypeItem>find("contractuuid", invoice.getContractuuid())
                .list().forEach(ct -> cti.put(ct.getKey(), ct.getValue()));

        var pr = pricingEngine.price(invoice, cti);

        pr.syntheticItems.forEach(ii -> ii.setInvoiceuuid(invoice.getUuid()));
        InvoiceItem.persist(pr.syntheticItems);

        invoice.invoiceitems.addAll(pr.syntheticItems);
        invoice.sumBeforeDiscounts = pr.sumBeforeDiscounts.doubleValue();
        invoice.sumAfterDiscounts  = pr.sumAfterDiscounts.doubleValue();
        invoice.vatAmount          = pr.vatAmount.doubleValue();
        invoice.grandTotal         = pr.grandTotal.doubleValue();
        invoice.calculationBreakdown = pr.breakdown;
    }
}
