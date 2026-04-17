package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

/**
 * Resolves the billing entity for an invoice per SPEC-INV-001 §3.2:
 *   billing_entity = contract.billingClientUuid ?? contract.clientuuid
 *
 * If neither client UUID can be resolved to a Client record, a
 * {@link jakarta.ws.rs.BadRequestException} is thrown — the invoice cannot
 * be finalized and the message is surfaced to the user.
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
            throw new BadRequestException(
                    "This draft references a contract that no longer exists. "
                    + "The contract may have been deleted — start a new draft from an active contract.");
        }

        String billingClientUuid = contract.getBillingClientUuid() != null
                ? contract.getBillingClientUuid()
                : contract.getClientuuid();

        Client billingClient = clientService.findByUuid(billingClientUuid);
        if (billingClient == null) {
            throw new BadRequestException(
                    "The billing entity on contract '" + contract.getName() + "' was not found. "
                    + "Open the contract and select a valid Billing Entity, then try again.");
        }

        return new BillingContext(invoice, contract, billingClient);
    }
}
