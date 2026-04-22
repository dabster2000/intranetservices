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
 * Resolves the billing entity for an invoice.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If {@code invoice.billingClientUuid} is non-null, load and return that
 *       Client. This branch covers INTERNAL / INTERNAL_SERVICE invoices which
 *       are stamped at creation time (see {@link IntercompanyClientResolver})
 *       — the contract is loaded but not consulted for the billing entity.
 *       SPEC: internal-invoice-billing-client-fix § FR-3.</li>
 *   <li>Otherwise (regular INVOICE / PHANTOM / CREDIT_NOTE flow) fall back to
 *       SPEC-INV-001 §3.2: {@code contract.billingClientUuid ?? contract.clientuuid}.</li>
 * </ol>
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
     * @throws BadRequestException when the contract or billing client cannot be found.
     */
    public BillingContext resolve(Invoice invoice) {
        Contract contract = contractService.findByUuid(invoice.getContractuuid());
        if (contract == null) {
            throw new BadRequestException(
                    "This draft references a contract that no longer exists. "
                    + "The contract may have been deleted — start a new draft from an active contract.");
        }

        // FR-3 — invoice-stamped billing client takes precedence over the
        // contract-based resolution. Populated at creation time for INTERNAL /
        // INTERNAL_SERVICE and re-stamped at createDraft for regular INVOICE.
        if (invoice.getBillingClientUuid() != null && !invoice.getBillingClientUuid().isBlank()) {
            Client stampedClient = clientService.findByUuid(invoice.getBillingClientUuid());
            if (stampedClient == null) {
                throw new BadRequestException(
                        "The billing entity stamped on this invoice was not found "
                        + "(billingClientUuid=" + invoice.getBillingClientUuid() + "). "
                        + "The Client row may have been deleted — contact finance.");
            }
            return new BillingContext(invoice, contract, stampedClient);
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
