package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;

/**
 * Immutable value carrier for the resolved billing context of an invoice.
 * Assembled by {@link BillingContextResolver} before the orchestrator builds
 * the DraftContext for the e-conomic mapper.
 *
 * SPEC-INV-001 §3.2.
 */
public record BillingContext(Invoice invoice, Contract contract, Client billingClient) {}
