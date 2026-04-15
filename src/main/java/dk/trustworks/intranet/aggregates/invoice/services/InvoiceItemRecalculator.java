package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
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
 * Extracts the recalculateInvoiceItems logic from InvoiceService so it can be
 * injected and mocked independently in the orchestrator test.
 *
 * TODO(phase-H-followup): real impl — currently delegates to InvoiceService.
 *   Once InvoiceService is refactored (H10), this becomes the canonical home.
 */
@ApplicationScoped
public class InvoiceItemRecalculator {

    @Inject
    EntityManager em;

    @Inject
    PricingEngine pricingEngine;

    /**
     * Rebuilds the invoice items: deletes CALCULATED items, re-runs the pricing
     * engine, and repopulates the collection on the entity.
     *
     * TODO(phase-H-followup): real impl — mirror the private recalculateInvoiceItems
     *   method in InvoiceService. Currently a no-op stub so the orchestrator compiles.
     */
    @Transactional
    public void recalculateInvoiceItems(Invoice invoice) {
        // TODO(phase-H-followup): real impl — port private recalculateInvoiceItems from InvoiceService
    }
}
