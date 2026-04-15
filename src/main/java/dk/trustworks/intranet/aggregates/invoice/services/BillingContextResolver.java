package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves the billing entity for an invoice per SPEC-INV-001 §3.2:
 *   billing_entity = contract.billingClientUuid ?? contract.clientuuid
 *
 * If neither client UUID can be resolved to a Client record, an
 * {@link IllegalStateException} is thrown — the invoice cannot be finalized.
 */
@ApplicationScoped
public class BillingContextResolver {

    @Inject
    ContractService contractService;

    @Inject
    ClientService clientService;

    /**
     * Resolves the full billing context (invoice + contract + billing client).
     *
     * @throws IllegalStateException when the contract or billing client cannot be found.
     */
    public BillingContext resolve(Invoice invoice) {
        Contract contract = contractService.findByUuid(invoice.getContractuuid());
        if (contract == null) {
            throw new IllegalStateException(
                    "No contract found for invoice " + invoice.getUuid()
                    + " (contractuuid=" + invoice.getContractuuid() + ")");
        }

        String billingClientUuid = contract.getBillingClientUuid() != null
                ? contract.getBillingClientUuid()
                : contract.getClientuuid();

        Client billingClient = clientService.findByUuid(billingClientUuid);
        if (billingClient == null) {
            throw new IllegalStateException(
                    "Billing client '" + billingClientUuid + "' not found for invoice "
                    + invoice.getUuid());
        }

        return new BillingContext(invoice, contract, billingClient);
    }
}
